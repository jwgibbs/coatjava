package org.jlab.service.eb;

import static java.lang.Math.abs;
import java.util.ArrayList;
import java.util.List;

import org.jlab.clas.detector.DetectorEvent;
import org.jlab.clas.detector.DetectorParticle;
import org.jlab.clas.detector.DetectorResponse;
import org.jlab.detector.base.DetectorType;

import org.jlab.clas.pdg.PhysicsConstants;
import org.jlab.clas.pdg.PDGDatabase;

import org.jlab.rec.eb.EBConstants;
import org.jlab.rec.eb.EBCCDBConstants;
import org.jlab.rec.eb.EBCCDBEnum;
import org.jlab.rec.eb.EBUtil;
import org.jlab.rec.eb.SamplingFractions;

/**
 * @author gavalian
 * @author jnewton
 * @author devita
 * @author baltzell
 */
public class EBAnalyzer {

    private EBCCDBConstants ccdb;

    static final int[]  PID_POSITIVE = new int[]{-11,  211, 321, 2212, 45};
    static final int[]  PID_NEGATIVE = new int[]{ 11, -211,-321,-2212};
    static final int[]  PID_NEUTRAL = new int[]{22,2112};

    public EBAnalyzer(EBCCDBConstants ccdb) {
        this.ccdb=ccdb;
    }

    
    /**
     *
     * Determine event start time from FT electron and reassign timing-based
     * particle identification accordingly.
     * 
     * Choice of which FT electron to use is that with the smallest vertex
     * time difference between FT and any combination of FD charged particle
     * and pid (mass) hypothesis.
     *
     * WARNING:  Here we hijack the event's particles, overwriting their pids,
     * rather than making copies, since particle ordering is critical for
     * shadow banks to work as intended, so they should have been written to
     * REC::Particle bank already.
     *
     */
    public void processEventFT(DetectorEvent event) {

        if (event.getParticles().size() <= 0) return;

        // An FD electron was already used to get start time, abort:
        if (event.getEventHeader().getStartTime()>0 &&
            event.getParticle(0).getPid()==11 &&
            event.getParticle(0).getStatus().isForward()) return;

        // Match FT against these hypotheses in FD:
        final int[] hypotheses = new int[]{-11,11,-211,211,2212};
    
        // particle candidates for FT-FD time-matching:
        List<DetectorParticle> electronFT = new ArrayList<DetectorParticle>();
        List<DetectorParticle> chargedFD = new ArrayList<DetectorParticle>();

        // corresponding response candidates, to avoid excessive searches during combinatorics:
        List<DetectorResponse> electronFTC = new ArrayList<DetectorResponse>();
        List<DetectorResponse> chargedFTOF = new ArrayList<DetectorResponse>();

        // load the candidates:
        for (DetectorParticle p : event.getParticles()) {
            if (p.getStatus().isTagger() && p.getPid()==11) {
                DetectorResponse ftc = p.getHit(DetectorType.FTCAL);
                if (ftc==null) throw new RuntimeException("FT electron without FTC!");
                electronFT.add(p);
                electronFTC.add(ftc);
            }
            else if (p.getStatus().isForward() && p.getCharge()!=0) {
                DetectorResponse ftof1a = p.getHit(DetectorType.FTOF,2);
                if (ftof1a==null) continue;
                chargedFD.add(p);
                chargedFTOF.add(ftof1a);
            }
        }

        // no good candidates, abort:
        if (electronFT.size()<1 || chargedFD.size()<1) return;
        
        // the index of the FT particle with the best FT-FD timing-match:
        int iMinTimeDiffFT = -1;

        // anything relevant must be better than half-bucket:
        // FIXME:  get this from EBCCDBEnum.RF_BUCKET_LENGTH/2
        double minTimeDiff = 2.0;

        // loop over FT electron candidates:
        for (int itag=0; itag<electronFT.size(); itag++) {

            // the FT calorimeter hit for this FT electron:
            final DetectorResponse ftc = electronFTC.get(itag);

            // this would be a straight line from (0,0,0): 
            final double pathFT = ftc.getPosition().mag();

            // assume beta=1 and calculate vertex time:
            final double vtxTimeFT = ftc.getTime() -
                pathFT/PhysicsConstants.speedOfLight();

            // calculate RF-corrected FT start time:
            // should we really compare against RF-corrected time?
            // or would comparing vertex times make more sense, and
            // then correcting for RF after choosing the best match?
            //double deltatr = - vtxTimeFT + rfTime + (1000+0.5)*rfPeriod;
            //double rfCorr     = deltatr % rfPeriod - rfPeriod/2;             
            
            // loop over FD charged particles:
            for (int ifd=0; ifd<chargedFD.size(); ifd++) {

                final DetectorResponse ftof1a = chargedFTOF.get(ifd);
                final double pFD = chargedFD.get(ifd).vector().mag();
                final double pathFD = chargedFD.get(ifd).getPathLength(ftof1a.getPosition());
                    
                for (int pid : hypotheses) {

                    // ignore if measured charge doesn't match hypothesis charge:
                    if (PDGDatabase.getParticleById(pid).charge() !=
                            chargedFD.get(ifd).getCharge()) {
                        continue;
                    }
                    
                    // calculate beta and vertex time based on measured
                    // momentum and this pid hypothesis:
                    final double beta = pFD /
                            Math.sqrt(pFD*pFD + Math.pow(PDGDatabase.getParticleMass(pid),2));
                    final double vtxTimeFD = ftof1a.getTime() -
                        pathFD/PhysicsConstants.speedOfLight()/beta;

                    // check for a new best FT-FD timing match:
                    if (Math.abs(vtxTimeFD-vtxTimeFT) < Math.abs(minTimeDiff)) {
                        minTimeDiff = vtxTimeFD-vtxTimeFT;
                        iMinTimeDiffFT = itag;
                    }
                }
            }
        }
       
        System.err.println("dog");

        // no good FT, abort:
        if (iMinTimeDiffFT<0) return;

        // set start time:
        // FIXME:  get real RF-corrected start time,
        // after centralizing RF-correction over in EBRadioFrequency
        // to not duplicate code again ...
        event.getEventHeader().setStartTimeFT(125);
        
        // reassign trigger particle:
        for (DetectorParticle p : event.getParticles()) {
            p.setTriggerParticle(false);
        }
        electronFT.get(iMinTimeDiffFT).setTriggerParticle(true);

        // recalculate betas, pids, etc:
        this.assignBetas(event,true);
        this.assignPids(event,true);
        this.assignNeutralMomenta(event);
    }
    
    public void processEvent(DetectorEvent event) {

        // abort, rely on default init of DetectorEvent:
        if (event.getParticles().size() <= 0) return;

        // first particle is designated as the "trigger" particle:
        DetectorParticle trigger = event.getParticle(0);

        // priority is to identify a trigger time:
        boolean foundTriggerTime=false;
        double startTime=-1000;

        // electron/positron/pion is trigger particle:
        if (trigger.getPid()==11 || trigger.getPid()==-11 ||
            trigger.getPid()==211 || trigger.getPid()==-211) {

            trigger.setBeta(trigger.getTheoryBeta(trigger.getPid()));
            trigger.setMass(PDGDatabase.getParticleById(trigger.getPid()).mass());

            double time = 0.0;
            double path = 0.0;

            // prefer FTOF Panel 1B:
            if (trigger.hasHit(DetectorType.FTOF, 2)==true){
                time = trigger.getTime(DetectorType.FTOF, 2);
                path = trigger.getPathLength(DetectorType.FTOF, 2);
                foundTriggerTime = true;
            }

            // else use FTOF Panel 1A:
            else if (trigger.hasHit(DetectorType.FTOF, 1)==true){
                time = trigger.getTime(DetectorType.FTOF, 1);
                path = trigger.getPathLength(DetectorType.FTOF, 1);
                foundTriggerTime = true;
            }
            
            // set startTime based on FTOF:
            if (foundTriggerTime) {
                final double tgpos = ccdb.getDouble(EBCCDBEnum.TARGET_POSITION);
                final double rfOffset = ccdb.getDouble(EBCCDBEnum.RF_OFFSET);
                final double rfBucketLength = ccdb.getDouble(EBCCDBEnum.RF_BUCKET_LENGTH); 

                final double tof = path/PhysicsConstants.speedOfLight()/trigger.getBeta();
                final double vertexTime = time - tof;

                final double vzCorr = 0;//(tgpos - trigger.vertex().z()) / PhysicsConstants.speedOfLight();

                final double deltatr = - vertexTime + event.getEventHeader().getRfTime() - vzCorr +
                    + (EBConstants.RF_LARGE_INTEGER+0.5)*rfBucketLength;
                final double rfCorr = deltatr % rfBucketLength - rfBucketLength/2;
                
                startTime = vertexTime + rfCorr;
            }
        }

        // neutral is trigger particle:
        else if (trigger.getPid()==0 || trigger.getPid()==22) {
            trigger.setBeta(1.0);
            trigger.setMass(0.0);
            // TODO:  implement full neutral trigger start time?
            //foundTriggerTime=true;
        }

        // we found event start time, so set it and do pid:
        if (foundTriggerTime) {
            event.getEventHeader().setStartTime(startTime);
            this.assignBetas(event,false);
            this.assignPids(event,false);
            this.assignNeutralMomenta(event);
        }

    }

    public void assignNeutralMomenta(DetectorEvent de) {
        final int np = de.getParticles().size();
        for (int ii=0; ii<np; ii++) {
            if (de.getParticle(ii).getCharge() != 0) continue;
            DetectorParticle p = de.getParticle(ii);
            switch (abs(p.getPid())) {
                case 2112:
                    // neutron momentum defined by measured beta if valid:
                    final double beta = p.getBeta();
                    if (beta>0 && beta<1) {
                        final double mass = PDGDatabase.getParticleById(p.getPid()).mass();
                        final double psquared = Math.pow(mass*beta,2) / (1-beta*beta);
                        p.vector().setMag( Math.sqrt(psquared) );
                    }
                    else {
                        p.vector().setMag(0.0);
                    }
                    break;
                case 22:
                    if (p.hasHit(DetectorType.ECAL)) {
                        // ECAL photon momentum defined by measured energy:
                        p.vector().setMag(p.getEnergy(DetectorType.ECAL) /
                            SamplingFractions.getMean(22,p,ccdb));
                    }
                    else if (p.hasHit(DetectorType.CND)) {
                        // CND has no handle on photon energy, so we set momentum to zero,
                        // and let user get direction from REC::Scintillator.x/y/z.
                        p.vector().setMag(0.0);
                    }
                    break;
                case 0:
                    // neutrals without a good pid get zero momentum:
                    p.vector().setMag(0.0);
                    break;
                default:
                    throw new RuntimeException("assignNeutralMomentum:  not ready for pid="+p.getPid());
            }
        }
    }

    public void assignBetas(DetectorEvent event,final boolean useStartTimeFromFT){

        final double startTime = useStartTimeFromFT ?
            event.getEventHeader().getStartTimeFT() :
            event.getEventHeader().getStartTime();
        final int np = event.getParticles().size();

        for (DetectorParticle p : event.getParticles()) {
            double beta = -99;
            if (p.isTriggerParticle()) {
                final double mass = PDGDatabase.getParticleById(p.getPid()).mass();
                final double mom  = p.vector().mag();
                beta = mom / Math.sqrt(mass*mass+mom*mom);
            }
            else {
                if (p.getCharge()==0) {
                    if (p.hasHit(DetectorType.ECAL)) {
                        // NOTE: prioritized by layer: PCAL, else Inner, else Outer
                        beta = EBUtil.getNeutralBeta(p,DetectorType.ECAL,new int[]{1,4,7},startTime);
                    }
                    else if (p.hasHit(DetectorType.CND)) {
                        beta = EBUtil.getNeutralBeta(p,DetectorType.CND,0,startTime);
                    }
                    else if (p.hasHit(DetectorType.FTCAL)) {
                        beta = EBUtil.getNeutralBeta(p,DetectorType.FTCAL,0,startTime);
                    }
                }
                else {
                    if (p.hasHit(DetectorType.FTOF, 2)==true){
                        beta = p.getBeta(DetectorType.FTOF,2, startTime);
                    }
                    else if(p.hasHit(DetectorType.FTOF, 1)==true){
                        beta = p.getBeta(DetectorType.FTOF, 1,startTime);
                    }
                    else if(p.hasHit(DetectorType.CTOF)==true){
                        beta = p.getBeta(DetectorType.CTOF ,startTime);
                    }
                    else if(p.hasHit(DetectorType.FTOF, 3)==true){
                        beta = p.getBeta(DetectorType.FTOF, 3,startTime);
                    }
                }
            }
            p.setBeta(beta);
        }
/*
        // NOTE:  this loop skips 0 because it's the trigger particle
        for(int i = 1; i < np; i++) {

            DetectorParticle p = event.getParticle(i);
            double beta = -9999;
            if (p.getCharge()==0) {
                if (p.hasHit(DetectorType.ECAL)) {
                    // NOTE: prioritized by layer: PCAL, else Inner, else Outer
                    beta = EBUtil.getNeutralBeta(p,DetectorType.ECAL,new int[]{1,4,7},startTime);
                }
                else if (p.hasHit(DetectorType.CND)) {
                    beta = EBUtil.getNeutralBeta(p,DetectorType.CND,0,startTime);
                }
                else if (p.hasHit(DetectorType.FTCAL)) {
                    beta = EBUtil.getNeutralBeta(p,DetectorType.FTCAL,0,startTime);
                }
            }
            else {
                if (p.hasHit(DetectorType.FTOF, 2)==true){
                    beta = p.getBeta(DetectorType.FTOF,2, startTime);
                }
                else if(p.hasHit(DetectorType.FTOF, 1)==true){
                    beta = p.getBeta(DetectorType.FTOF, 1,startTime);
                }
                else if(p.hasHit(DetectorType.CTOF)==true){
                    beta = p.getBeta(DetectorType.CTOF ,startTime);
                }
                else if(p.hasHit(DetectorType.FTOF, 3)==true){
                    beta = p.getBeta(DetectorType.FTOF, 3,startTime);
                }
            }
            p.setBeta(beta);
        }
*/
    }

    public void assignPids(DetectorEvent event,final boolean useStartTimeFromFT) {

        PIDHypothesis pidHyp = new PIDHypothesis();
        pidHyp.setEvent(event);
        pidHyp.setUseStartTimeFromFT(useStartTimeFromFT);

        for (DetectorParticle p : event.getParticles()) {
            if (p.isTriggerParticle()) {
                pidHyp.finalizePID(p,p.getPid());
            }
            else {
                if (p.getCharge()>0){
                    for(int b = 0; b < this.PID_POSITIVE.length; b++) {
                        pidHyp.PIDMatch(p, this.PID_POSITIVE[b]); 
                    }
                } 
                else if (p.getCharge()<0) {
                    for(int b = 0; b < this.PID_NEGATIVE.length; b++) {
                        pidHyp.PIDMatch(p, this.PID_NEGATIVE[b]);
                    }
                }
                else {
                    for(int b = 0; b < this.PID_NEUTRAL.length; b++) {
                        pidHyp.PIDMatch(p, this.PID_NEUTRAL[b]);
                    }
                }
            }
        }
        /*
        // pid for trigger particle is already chosen,
        // just call this to set quality factor:
        pidHyp.finalizePID(event.getParticle(0),event.getParticle(0).getPid());

        // loop skips first (trigger) particle:
        final int np = event.getParticles().size();
        for(int i = 1; i < np; i++){
        
            DetectorParticle p = event.getParticle(i);
            
            if (p.getCharge()>0){
                for(int b = 0; b < this.PID_POSITIVE.length; b++) {
                    pidHyp.PIDMatch(p, this.PID_POSITIVE[b]); 
                }
            } 
            
            else if (p.getCharge()<0) {
                for(int b = 0; b < this.PID_NEGATIVE.length; b++) {
                    pidHyp.PIDMatch(p, this.PID_NEGATIVE[b]);
                }
                //Collections.sort(pidHyp); 
            }
            else {
                for(int b = 0; b < this.PID_NEUTRAL.length; b++) {
                    pidHyp.PIDMatch(p, this.PID_NEUTRAL[b]);
                }
            }

        }
        */
    }



    public class PIDHypothesis {

        private int theoryPID = -1;
        private double PIDquality = 0.0;
        private DetectorEvent event;
        private boolean useStartTimeFromFT = false;

        public PIDHypothesis() {}

        public void setEvent(DetectorEvent e) {event = e;}

        public void setUseStartTimeFromFT(boolean b) {useStartTimeFromFT = b;}

        public double getStartTime() {
            return useStartTimeFromFT ?
                event.getEventHeader().getStartTimeFT() :
                event.getEventHeader().getStartTime();
        }

        public void PIDMatch(DetectorParticle p, int pid) {

            final int pidFromTiming = bestPidFromTiming(p);
            
            final boolean pidFromTimingCheck = pid==pidFromTiming && p.getTheoryBeta(pid)>0;
           
            final boolean isElectron = EBUtil.isSimpleElectron(p,ccdb);
            
            final boolean htccSignalCheck = p.getNphe(DetectorType.HTCC)>EBConstants.HTCC_NPHE_CUT;
            final boolean ltccSignalCheck = p.getNphe(DetectorType.LTCC)>EBConstants.LTCC_NPHE_CUT;
            
            final boolean htccPionThreshold = p.vector().mag()>EBConstants.HTCC_PION_THRESHOLD;
            final boolean ltccPionThreshold = p.vector().mag()>EBConstants.LTCC_PION_THRESHOLD;
            final boolean ltccKaonThreshold = p.vector().mag()>EBConstants.LTCC_KAON_THRESHOLD;

            switch(abs(pid)) {
                case 11:
                    // require htcc nphe and ecal sampling fraction for electrons: 
                    if(isElectron) {
                        this.finalizePID(p, pid);
                    }
                    break;

                case 211:
                    if (pidFromTimingCheck && !isElectron) {
                        // pion is best timing
                        this.finalizePID(p,pid);
                    }
                    else if (!isElectron && htccSignalCheck && htccPionThreshold) {
                        // pion is not the best timing, but htcc signal and above pion threshold:
                        this.finalizePID(p,pid);
                    }
                    break;
                case 321:
                    if (pidFromTimingCheck && !isElectron) {
                        // kaon is best timing
                        if (ltccSignalCheck && ltccPionThreshold) {
                            // let ltcc veto back to pion:
                            this.finalizePID(p,(pid>0?211:-211));
                        }
                        else {
                            this.finalizePID(p,pid);
                        }
                    }
                    break;
                case 2212:
                    if (pidFromTimingCheck && !isElectron) {
                        // proton is best timing
                        if (ltccSignalCheck && ltccPionThreshold) {
                            // let ltcc veto back to pion:
                            this.finalizePID(p,(pid>0?211:-211));
                        }
                        else {
                            this.finalizePID(p,pid);
                        }
                    }
                    break;
                case 45:
                    if (pidFromTimingCheck && !isElectron) {
                        this.finalizePID(p,pid);
                    }
                    break;

                case 2112:
                    if (pidFromTimingCheck) {
                        this.finalizePID(p,pid);
                    }
                    break;
                case 22:
                    if (pidFromTimingCheck) {
                        this.finalizePID(p,pid);
                    }
                    break;
            }

        }

        /**
         * Get the hadron hypotheses with the closest vertex time.
         */
        public int bestPidFromTiming(DetectorParticle p) {
            int bestPid=0;
            if (p.getCharge() == 0) {
                if (p.hasHit(DetectorType.ECAL)) {
                    bestPid = p.getBeta()<ccdb.getDouble(EBCCDBEnum.NEUTRON_maxBeta) ? 2112 : 22;
                }
                else if (p.hasHit(DetectorType.CND)) {
                    bestPid = p.getBeta()<ccdb.getDouble(EBCCDBEnum.CND_NEUTRON_maxBeta) ? 2112 : 0;
                }
            }
            else {
                int[] hypotheses;
                if      (p.getCharge()>0) hypotheses=PID_POSITIVE;
                else                      hypotheses=PID_NEGATIVE;
                //final double startTime = event.getEventHeader().getStartTime();
                final double startTime = this.getStartTime();
                double minTimeDiff=Double.MAX_VALUE;
                for (int ii=0; ii<hypotheses.length; ii++) {
                    if (abs(hypotheses[ii])==11) continue;
                    double dt=Double.MAX_VALUE;
                    if (p.hasHit(DetectorType.FTOF,2)==true)
                        dt = p.getVertexTime(DetectorType.FTOF,2,hypotheses[ii]) - startTime;
                    else if (p.hasHit(DetectorType.FTOF,1)==true)
                        dt = p.getVertexTime(DetectorType.FTOF,1,hypotheses[ii]) - startTime;
                    else if (p.hasHit(DetectorType.CTOF)==true)
                        dt = p.getVertexTime(DetectorType.CTOF,0,hypotheses[ii]) - startTime;
                    else if (p.hasHit(DetectorType.FTOF,3)==true)
                        dt = p.getVertexTime(DetectorType.FTOF,3,hypotheses[ii]) - startTime;
                    if ( abs(dt) < minTimeDiff ) {
                        minTimeDiff=abs(dt);
                        bestPid=hypotheses[ii];
                    }
                }
            }
            return bestPid;
        }


        /**
         * Get a basic pid quality factor.
         */
        public double PIDQuality(DetectorParticle p, int pid, DetectorEvent event) {
            double q=DetectorParticle.DEFAULTQUALITY;

            // electron/positron:
            if (abs(pid)==11) {
                q = SamplingFractions.getNSigma(pid,p,ccdb);
            }

            // based on timing:
            else if (p.getCharge()!=0) {
                final double startTime = this.getStartTime();
                //final double startTime = event.getEventHeader().getStartTime();
                double sigma = -1;
                double delta_t = 99999;
                if (p.hasHit(DetectorType.FTOF,2)==true) {
                    sigma = EBUtil.getDetTimingResolution(p.getHit(DetectorType.FTOF,2),ccdb);
                    delta_t = p.getVertexTime(DetectorType.FTOF, 2, pid)-startTime;
                }
                else if (p.hasHit(DetectorType.FTOF,1)==true) {
                    sigma = EBUtil.getDetTimingResolution(p.getHit(DetectorType.FTOF,1),ccdb);
                    delta_t = p.getVertexTime(DetectorType.FTOF, 1, pid)-startTime;
                }
                else if (p.hasHit(DetectorType.CTOF)==true) {
                    sigma = EBUtil.getDetTimingResolution(p.getHit(DetectorType.CTOF,0),ccdb);
                    delta_t = p.getVertexTime(DetectorType.CTOF, 0, pid)-startTime;
                }
                else if (p.hasHit(DetectorType.FTOF,3)==true) {
                    sigma = EBUtil.getDetTimingResolution(p.getHit(DetectorType.FTOF,3),ccdb);
                    delta_t = p.getVertexTime(DetectorType.FTOF, 3, pid)-startTime;
                }
                q = delta_t / sigma;
            }

            // neutrals:
            else {
            }

            return q;
        }


        /**
         * Set particle's pid and quality factor.
         */
        public void finalizePID(DetectorParticle p, int pid) {
            p.setPid(pid);
            theoryPID = pid;
            PIDquality = this.PIDQuality(p, pid, event);
            p.setPidQuality(PIDquality);

        }
        
    }
}



