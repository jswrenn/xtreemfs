/*
 * Copyright (c) 2012-2013 by Jens V. Fischer, Zuse Institute Berlin
 *               
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.sandbox.benchmark;

import org.xtreemfs.common.libxtreemfs.Volume;

/**
 * Abstract baseclass for filebased benchmarks.
 * <p/>
 * A filebased benchmark writes or reads lots of small files.
 * 
 * @author jensvfischer
 */
public abstract class BenchmarkFilebased extends BenchmarkRandom {

    static final String BENCHMARK_FILENAME = "benchmarks/randomBenchmark/benchFile";
    final int           randomIOFilesize;

    public BenchmarkFilebased(Volume volume, Params params) throws Exception {
        super(volume, params);
        this.randomIOFilesize = params.randomIOFilesize;
    }

    /* convert to 4 KiB Blocks */
    protected long convertTo4KiBBlocks(long numberOfBlocks) {
        return (numberOfBlocks * (long) stripeWidth) / (long) randomIOFilesize;
    }
}
