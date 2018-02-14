/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.monacoinj.params;

import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.monacoinj.core.Block;
import org.monacoinj.core.Coin;
import org.monacoinj.core.NetworkParameters;
import org.monacoinj.core.Sha256Hash;
import org.monacoinj.core.StoredBlock;
import org.monacoinj.core.Transaction;
import org.monacoinj.core.Utils;
import org.monacoinj.utils.MonetaryFormat;
import org.monacoinj.core.VerificationException;
import org.monacoinj.store.BlockStore;
import org.monacoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import org.monacoinj.core.MonacoinSerializer;

import java.util.Date;
import java.io.RandomAccessFile;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.monacoinj.utils.Threading;

/**
 * Parameters for Monacoin-like networks.
 */
public abstract class AbstractMonacoinNetParams extends NetworkParameters {

    /**
     * Scheme part for Monacoin URIs.
     */
    public static final String MONACOIN_SCHEME = "monacoin";
    public static final int REWARD_HALVING_INTERVAL = 210000;

    private static final Logger log = LoggerFactory.getLogger(AbstractMonacoinNetParams.class);
    protected final ReentrantLock lock = Threading.lock("blockchain");

    /** Keeps a map of block hashes to StoredBlocks. */
    protected final BlockStore blockStore = null;

    public AbstractMonacoinNetParams() {
        super();
    }

    /**
     * Checks if we are at a reward halving point.
     * @param height The height of the previous stored block
     * @return If this is a reward halving point
     */
    public final boolean isRewardHalvingPoint(final int height) {
        return ((height + 1) % REWARD_HALVING_INTERVAL) == 0;
    }

    /**
     * Checks if we are at a difficulty transition point.
     * @param height The height of the previous stored block
     * @return If this is a difficulty transition point
     */
    public final boolean isDifficultyTransitionPoint(final int height) {
        return ((height + 1) % this.getInterval()) == 0;
    }

    // from https://github.com/monapu/monacoinj-multibit

    // February 16th 2012
    private static final Date testnetDiffDate = new Date(1329264000000L);

    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final Block nextBlock,
    	final BlockStore blockStore) throws VerificationException, BlockStoreException {
        final Block prev = storedPrev.getHeader();

        if((storedPrev.getHeight() + 1) >= this.getSwitchDGWV3Block()) {   
            DarkGravityWave3( storedPrev , nextBlock);
            return;
        }else if((storedPrev.getHeight() + 1) >= this.getSwitchKGWBlock() 
           && (storedPrev.getHeight() + 1) < this.getSwitchDigishieldBlock()) {
            checkDifficultyTransitionsKGW( storedPrev , nextBlock);
            return;
        }
        
        boolean useDigishield = storedPrev.getHeight() + 1 >= this.getSwitchDigishieldBlock();
        int retargetInterval = this.getInterval();
        int retargetTimespan = this.getTargetTimespan();
        if(useDigishield){
            retargetInterval = this.getDigishieldInterval();
            retargetTimespan = this.getDigishieldTargetTimespan();
        }

        // Is this supposed to be a difficulty transition point?
        if ((storedPrev.getHeight() + 1) % retargetInterval != 0) {

            // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
            // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
            // for each network type. Then each network can define its own difficulty transition rules.
            if (this.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
                checkTestnetDifficulty(storedPrev, prev, nextBlock);
                return;
            }

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        long now = System.currentTimeMillis();
        StoredBlock cursor = blockStore.get(prev.getHash());
        int goBack = retargetInterval - 1;
        if (cursor.getHeight()+1 != retargetInterval)
            goBack = retargetInterval;

        for (int i = 0; i < goBack; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }

        //We used checkpoints...
        if(cursor == null)
        {
            log.debug("Difficulty transition: Hit checkpoint!");
            return;
        }

        long elapsed = System.currentTimeMillis() - now;
        if (elapsed > 50)
            log.info("Difficulty transition traversal took {}msec", elapsed);

        Block blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        final int targetTimespan = retargetTimespan;
        // Limit the adjustment step.
        if(useDigishield){
            timespan = retargetTimespan + (timespan - retargetTimespan)/8;
            if (timespan < (retargetTimespan - (retargetTimespan/4)) ) 
                timespan = (retargetTimespan - (retargetTimespan/4));
            if (timespan > (retargetTimespan + (retargetTimespan/2)) ) 
                timespan = (retargetTimespan + (retargetTimespan/2));
        }else{
            if (timespan < targetTimespan / 4)
                timespan = targetTimespan / 4;
            if (timespan > targetTimespan * 4)
                timespan = targetTimespan * 4;
        }
        BigInteger newDifficulty = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newDifficulty = newDifficulty.multiply(BigInteger.valueOf(timespan));
        newDifficulty = newDifficulty.divide(BigInteger.valueOf(targetTimespan));

        if (newDifficulty.compareTo(this.getProofOfWorkLimit()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = this.getProofOfWorkLimit();
        }

        if (!verifyTarget( nextBlock , newDifficulty)){
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                            nextBlock.getDifficultyTargetAsInteger().toString(16) + 
                                            " vs " + newDifficulty.toString(16));
        }
    }

    private strictfp void checkDifficultyTransitionsKGW(StoredBlock blockLastSolved, Block nextBlock) throws BlockStoreException, VerificationException {

        long timeDaySeconds = 60 * 60 * 24;
        long pastSecondsMin = timeDaySeconds / 4 ;
        long pastSecondsMax = timeDaySeconds * 7;
        long pastBlocksMin  = pastSecondsMin / NetworkParameters.TARGET_SPACING;
        long pastBlocksMax  = pastSecondsMax / NetworkParameters.TARGET_SPACING;

        long pastRateActualSeconds = 0;
        long pastRateTargetSeconds = 0;

        BigInteger newTarget = null;

        double eventHorizonDeviation;
        double eventHorizonDeviationFast;
        double eventHorizonDeviationSlow;

        // Block blockLastSolvedHeader = blockLastSolved.getHeader();
        
        StoredBlock blockReading = blockLastSolved;
        BigInteger readingDifficultyTargetAsInteger = BigInteger.ZERO;
        long readingTimeSeconds = -1;
        long readingHeight = -1;
        if(blockReading != null){
            readingDifficultyTargetAsInteger = blockReading.getHeader().getDifficultyTargetAsInteger();
            readingTimeSeconds = blockReading.getHeader().getTimeSeconds();
            readingHeight = blockReading.getHeight();
        }
        
        long pastBlocksMass = 0;
        double pastRateAdjustmentRatio = 1.0D ;
        BigInteger pastTargetAverage = null;
        BigInteger pastTargetAveragePrev = null;

        if( blockLastSolved == null
            || blockLastSolved.getHeight() == 0
            || blockLastSolved.getHeight() < pastBlocksMin ){
            newTarget = this.getProofOfWorkLimit();
        } else {
            
            for( long i = 1; readingHeight > 0; i++){
                if( pastBlocksMax > 0 && i > pastBlocksMax ) 
                    break;
                pastBlocksMass++;
                BigInteger thisTarget = readingDifficultyTargetAsInteger;
                if( i == 1) {
                    pastTargetAverage = thisTarget;
                } else {
                    pastTargetAverage = 
                        pastTargetAveragePrev.
                        add( thisTarget.subtract(pastTargetAveragePrev).
                             divide( BigInteger.valueOf(i)) );
                }
                pastTargetAveragePrev = pastTargetAverage;
                // c++ のGetBlockTimeは unix時間
                pastRateActualSeconds =
                    blockLastSolved.getHeader().getTimeSeconds() - 
                    readingTimeSeconds;
                pastRateTargetSeconds =
                     NetworkParameters.TARGET_SPACING * pastBlocksMass;
                pastRateAdjustmentRatio = 1D;
                if( pastRateActualSeconds < 0) pastRateActualSeconds = 0;
                if( pastRateActualSeconds != 0 && pastRateTargetSeconds != 0){
                    pastRateAdjustmentRatio = 
                        Double.valueOf(pastRateTargetSeconds) / 
                        Double.valueOf(pastRateActualSeconds) ;
                }
                
                eventHorizonDeviation = 
                    1 + (0.7084 * Math.pow(Double.valueOf(pastBlocksMass)/144D, -1.228)); // KGW formula
                eventHorizonDeviationFast = eventHorizonDeviation;
                eventHorizonDeviationSlow = 1 / eventHorizonDeviation;
                
                if( pastBlocksMass >= pastBlocksMin ){
                    if( (pastRateAdjustmentRatio <= eventHorizonDeviationSlow) ||
                        (pastRateAdjustmentRatio >= eventHorizonDeviationFast)) {
                        break;
                    }
                }

                StoredBlock prevBlock = null;
                if(blockReading != null)
                    prevBlock = blockReading.getPrev(blockStore);

                if(prevBlock == null){
                    readingHeight--;
                    TargetAndTime targetAndTime = getTargetAndTimeFromFile( readingHeight );
                    if( targetAndTime == null){
                        log.info("give up to verify KGW target value. no blockdata {}" , readingHeight );
                        return;
                    }
                    readingDifficultyTargetAsInteger = targetAndTime.target;
                    readingTimeSeconds = targetAndTime.time;
                    blockReading = null;

                } else {
                    blockReading = prevBlock;
                    readingDifficultyTargetAsInteger = 
                        blockReading.getHeader().getDifficultyTargetAsInteger();
                    readingTimeSeconds = blockReading.getHeader().getTimeSeconds();
                    readingHeight = blockReading.getHeight();
                }
            } // for
            
            newTarget = pastTargetAverage;
            if( pastRateActualSeconds != 0 && pastRateTargetSeconds != 0){
                newTarget = 
                    newTarget.multiply( BigInteger.valueOf(pastRateActualSeconds) ).
                    divide( BigInteger.valueOf(pastRateTargetSeconds) );
                
            }
            if( newTarget.compareTo(this.getProofOfWorkLimit()) == 1 )
                newTarget = this.getProofOfWorkLimit();
        } // if
        
        // log.info("pastBlocksMass:{}" , pastBlocksMass);

        if( !verifyTarget( nextBlock , newTarget)){
            throw new VerificationException("Network provided difficulty bits do not match what was calculated(KGW): " +
                                            nextBlock.getDifficultyTargetAsInteger().toString(16) + 
                                            " vs " + newTarget.toString(16));
        }
        
    }
    
    private void DarkGravityWave3(StoredBlock storedPrev, Block nextBlock) {
        /* current difficulty formula, darkcoin - DarkGravity v3, written by Evan Duffield - evan@darkcoin.io */
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;
        Block BlockCreating = nextBlock;
        BlockCreating = BlockCreating;
        long nActualTimespan = 0;
        long LastBlockTime = 0;
        long PastBlocksMin = 24;
        long PastBlocksMax = 24;
        long CountBlocks = 0;
        BigInteger PastDifficultyAverage = BigInteger.ZERO;
        BigInteger PastDifficultyAveragePrev = BigInteger.ZERO;

        if (BlockLastSolved == null || BlockLastSolved.getHeight() < this.getSwitchDGWV3Block() + PastBlocksMin) {
            verifyDifficulty(this.getProofOfWorkLimit(), storedPrev, nextBlock);
            return;
        }

        for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            CountBlocks++;

            if(CountBlocks <= PastBlocksMin) {
                if (CountBlocks == 1) { PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
                else { PastDifficultyAverage = ((PastDifficultyAveragePrev.multiply(BigInteger.valueOf(CountBlocks)).add(BlockReading.getHeader().getDifficultyTargetAsInteger()).divide(BigInteger.valueOf(CountBlocks + 1)))); }
                PastDifficultyAveragePrev = PastDifficultyAverage;
            }

            if(LastBlockTime > 0){
                long Diff = (LastBlockTime - BlockReading.getHeader().getTimeSeconds());
                nActualTimespan += Diff;
            }
            LastBlockTime = BlockReading.getHeader().getTimeSeconds();

            try {
                StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
                if (BlockReadingPrev == null)
                {
                    //assert(BlockReading); break;
                    return;
                }
                BlockReading = BlockReadingPrev;
            }
            catch(BlockStoreException x)
            {
                return;
            }
        }

        BigInteger bnNew= PastDifficultyAverage;

        long nTargetTimespan = CountBlocks*this.TARGET_SPACING;//nTargetSpacing;

        if (nActualTimespan < nTargetTimespan/3)
            nActualTimespan = nTargetTimespan/3;
        if (nActualTimespan > nTargetTimespan*3)
            nActualTimespan = nTargetTimespan*3;

        // Retarget
        bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
        bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan));
        verifyDifficulty(bnNew, storedPrev, nextBlock);
        
    }
    
    private void verifyDifficulty(BigInteger calcDiff, StoredBlock storedPrev, Block nextBlock)
    {
        if (calcDiff.compareTo(this.getProofOfWorkLimit()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", calcDiff.toString(16));
            calcDiff = this.getProofOfWorkLimit();
        }
        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        calcDiff = calcDiff.and(mask);
        if(this.getId().compareTo(this.ID_TESTNET) == 0)
        {
            if (calcDiff.compareTo(receivedDifficulty) != 0)
                throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                        receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
        }
        else
        {

            if (calcDiff.compareTo(receivedDifficulty) != 0)
                throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
            
        }
    }

    private class TargetAndTime {
        public BigInteger target = null;
        public long time = -1;
    }

    private RandomAccessFile targetAndTimeFile = null;
    private long targetAndTimeFileStart = -1;
    private LinkedHashMap<Long,TargetAndTime> targetAndTimeFileCache = null;
    private final int TARGET_CACHE_MAX = 7000;
    
    public void setTargetAndTimeFile( File path ){
        if(path.exists()){
            try{
                targetAndTimeFile = 
                    new RandomAccessFile( path , "r");
                byte[] buf = new byte[8];
                targetAndTimeFile.seek(0);
                targetAndTimeFile.readFully( buf , 0 , 8 );
                targetAndTimeFileStart = Utils.readInt64(buf , 0 );
                
                targetAndTimeFileCache = 
                    new LinkedHashMap<Long,TargetAndTime>(TARGET_CACHE_MAX){
                    protected boolean removeEldestEntry(Map.Entry<Long,TargetAndTime> eldest)  {
                        return size() > TARGET_CACHE_MAX;
                    }
                };
            } catch(Exception e) {
                log.error("failed to prepare outer target/time data file.");
            }
        }
    }
    private TargetAndTime getTargetAndTimeFromFile( long height ){

        TargetAndTime result = null;
        
        if( targetAndTimeFile != null
            && targetAndTimeFileStart > -1 
            && targetAndTimeFileStart <= height ){

            result = targetAndTimeFileCache.get( Long.valueOf(height) );
            if( result == null){
                result = new TargetAndTime();
                
                long pos = (height - targetAndTimeFileStart + 1 ) * 8;
                try {
                    byte[] buf = new byte[8];
                    targetAndTimeFile.seek( pos );
                    targetAndTimeFile.readFully( buf , 0 , 8 );
                    
                    result.target = Utils.decodeCompactBits(Utils.readUint32( buf , 0 ));
                    result.time   = Utils.readUint32( buf , 4 );
                    
                    synchronized(targetAndTimeFileCache) {
                        targetAndTimeFileCache.put( Long.valueOf(height) , result );
                    }
                    
                } catch (Exception e){
                    // log.error("can't read target/time data file at height {}" , height);
                }
            } else {
                //
            }
        }
        return result;
    }
    
    private void checkTestnetDifficulty(StoredBlock storedPrev, Block prev, Block next) throws VerificationException, BlockStoreException {
        checkState(lock.isHeldByCurrentThread());
        // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
        // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
        // blocks are allowed if there has been a span of 1 * targetSpacing sec without one.
        final long timeDelta = next.getTimeSeconds() - prev.getTimeSeconds();
        // There is an integer underflow bug in monacoin-qt that means mindiff blocks are accepted when time
        // goes backwards.
        if (timeDelta >= 0 && timeDelta <= NetworkParameters.TARGET_SPACING * 1) {
            // Walk backwards until we find a block that doesn't have the easiest proof of work, then check
            // that difficulty is equal to that one.
            StoredBlock cursor = storedPrev;
            // while (!cursor.getHeader().equals(params.getGenesisBlock()) &&
            while (cursor.getPrev(blockStore) != null &&
                   cursor.getHeight() % this.getInterval() != 0 &&
                   cursor.getHeader().getDifficultyTargetAsInteger().equals(this.getProofOfWorkLimit()))
                cursor = cursor.getPrev(blockStore);
            BigInteger cursorDifficulty = cursor.getHeader().getDifficultyTargetAsInteger();
            BigInteger newDifficulty = next.getDifficultyTargetAsInteger();
            if (!cursorDifficulty.equals(newDifficulty))
                throw new VerificationException("Testnet block transition that is not allowed: " +
                    Long.toHexString(cursor.getHeader().getDifficultyTarget()) + " vs " +
                    Long.toHexString(next.getDifficultyTarget()));
        }
    }

    private boolean verifyTarget( Block block , BigInteger newDifficulty )
        throws VerificationException
    {
        int accuracyBytes = (int) (block.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = block.getDifficultyTargetAsInteger();
        
        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        BigInteger newDifficulty2 = newDifficulty.and(mask);
        
        return (newDifficulty2.compareTo(receivedDifficulty) == 0 );
    }


    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat();
    }

    @Override
    public int getProtocolVersionNum(final ProtocolVersion version) {
        return version.getMonacoinProtocolVersion();
    }

    @Override
    public MonacoinSerializer getSerializer(boolean parseRetain) {
        return new MonacoinSerializer(this, parseRetain);
    }

    @Override
    public String getUriScheme() {
        return MONACOIN_SCHEME;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }
}
