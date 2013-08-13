/*
 * Copyright (c) 2012-2013 by Jens V. Fischer, Zuse Institute Berlin
 *               
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.sandbox.benchmark;

import java.util.LinkedList;
import java.util.Random;

import org.xtreemfs.common.libxtreemfs.FileHandle;
import org.xtreemfs.common.libxtreemfs.Volume;
import org.xtreemfs.common.libxtreemfs.exceptions.PosixErrorException;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes;

/**
 * Abstract baseclass for random IO benchmarks.
 * <p/>
 * Random IO benchmarks write or read small blocks with random offsets within a large basefile.
 * 
 * @author jensvfischer
 */
public abstract class BenchmarkRandomOffsetbased extends BenchmarkRandom {
    final static int    RANDOM_IO_BLOCKSIZE = 1024 * 4;             // 4 KiB
    static final String BENCHMARK_FILENAME  = "randomBenchFile";
    final long          sizeOfBasefile;
    final static String BASFILE_FILENAME    = "benchmarks/basefile";

    public BenchmarkRandomOffsetbased(Volume volume, Params params) throws Exception {
        super(volume, params);
        sizeOfBasefile = params.basefileSizeInBytes;
    }

    @Override
    void prepareBenchmark() throws Exception {
        /* create file to read from if not existing */
        if (basefileDoesNotExists()) {
            createBasefile();
        }
    }

    @Override
    void finalizeBenchmark() {
    }

    /* convert to 4 KiB Blocks */
    protected long convertTo4KiBBlocks(long numberOfBlocks) {
        return (numberOfBlocks * (long) stripeWidth) / (long) RANDOM_IO_BLOCKSIZE;
    }

    protected long generateNextRandomOffset() {
        long nextOffset = Math.round(Math.random() * (sizeOfBasefile - (long) RANDOM_IO_BLOCKSIZE));
        assert nextOffset >= 0 : "Offset < 0. Offset: " + nextOffset + " Basefilesize: " + sizeOfBasefile;
        assert nextOffset <= (sizeOfBasefile - RANDOM_IO_BLOCKSIZE) : " Offset > Filesize. Offset: " + nextOffset
                + "Basefilesize: " + sizeOfBasefile;
        return nextOffset;
    }

    /* check if a basefile to read from exists and if it has the right size */
    boolean basefileDoesNotExists() throws Exception {
        try {
            FileHandle fileHandle = volume.openFile(params.userCredentials, BASFILE_FILENAME,
                    GlobalTypes.SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_RDONLY.getNumber());
            long fileSizeInBytes = fileHandle.getAttr(params.userCredentials).getSize();
            fileHandle.close();
            return sizeOfBasefile != fileSizeInBytes;
        } catch (PosixErrorException e) {
            Logging.logMessage(Logging.LEVEL_INFO, Logging.Category.tool, this, "No basefile found.");
            return true;
        }
    }

    /* Create a large file to read from */
    private void createBasefile() throws Exception {
        Logging.logMessage(Logging.LEVEL_INFO, Logging.Category.tool, this,
                "Start creating a basefile of size %s bytes.", sizeOfBasefile);
        long numberOfBlocks = sizeOfBasefile / (long) stripeWidth;
        Random random = new Random();
        int flags = GlobalTypes.SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_CREAT.getNumber()
                | GlobalTypes.SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_TRUNC.getNumber()
                | GlobalTypes.SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_RDWR.getNumber();
        FileHandle fileHandle = volume.openFile(params.userCredentials, BASFILE_FILENAME, flags, 511);
        long byteCounter = 0;
        byte[] data = new byte[stripeWidth];
        for (long j = 0; j < numberOfBlocks; j++) {
            long nextOffset = j * stripeWidth;
            assert nextOffset >= 0 : "Offset < 0 not allowed";
            random.nextBytes(data);
            byteCounter += fileHandle.write(params.userCredentials, data, stripeWidth, nextOffset);
        }
        fileHandle.close();
        assert byteCounter == sizeOfBasefile : " Error while writing the basefile for the random io benchmark";

        addBasefileToCreatedFiles();

        Logging.logMessage(Logging.LEVEL_INFO, Logging.Category.tool, this, "Basefile written. Size %s Bytes.",
                byteCounter);

    }

    private void addBasefileToCreatedFiles() throws Exception {
        if (!params.noCleanupOfBasefile) {
            LinkedList<String> createdFiles = new LinkedList<String>();
            createdFiles.add(BASFILE_FILENAME);
            VolumeManager.getInstance().addCreatedFiles(volume, createdFiles);
        }
    }

}
