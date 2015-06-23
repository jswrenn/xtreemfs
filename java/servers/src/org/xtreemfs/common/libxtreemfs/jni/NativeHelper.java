/*
 * Copyright (c) 2015 by Johannes Dillmann, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.common.libxtreemfs.jni;

import java.io.File;
import java.math.BigInteger;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.common.libxtreemfs.jni.generated.ClientProxy;
import org.xtreemfs.common.libxtreemfs.jni.generated.OptionsProxy;
import org.xtreemfs.common.libxtreemfs.jni.generated.SSLOptionsProxy;
import org.xtreemfs.common.libxtreemfs.jni.generated.ServiceAddresses;
import org.xtreemfs.common.libxtreemfs.jni.generated.StringMap;
import org.xtreemfs.common.libxtreemfs.jni.generated.StringVector;
import org.xtreemfs.foundation.SSLOptions;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.KeyValuePair;

public final class NativeHelper {

    /**
     * Load the library with the platform independent name. (f.ex. jni-xtreemfs instead of libjni-xtreemfs.so) <br>
     * Locally built libraries from the source tree are preferred. On Linux feasible directories within the FHS are
     * searched. Finally the common library path is searched.
     * 
     * @param name
     */
    public static void loadLibrary(String name) {
        String libname = System.mapLibraryName(name);

        // Prefer recently build libs from the source tree.
        if (tryLoadLibraryFromBuildDir(libname)) {
            return;
        }

        // Try to find the correct lib directory within the Filesystem Hierarchy Standard
        String os = System.getProperty("os.name");
        if (os.equals("Linux")) {
            if (tryLoadLibraryFromFHS(libname)) {
                return;
            }
        }

        // Finally try to load the lib from the common library path.
        System.loadLibrary(name);
    }

    /**
     * Try to load the library from the build directory.
     * 
     * @param filename
     * @return true if the library has been loaded
     */
    private static boolean tryLoadLibraryFromBuildDir(String filename) {
        // Try to load the library directly from the build directory
        // Get the URL of the current class
        URL classURL = NativeHelper.class.getResource(NativeHelper.class.getSimpleName() + ".class");
        // Abort if the class isn't a real file (and not within f.ex. a jar)
        if (classURL == null) {
            return false;
        }

        String path;
        if ("file".equalsIgnoreCase(classURL.getProtocol())) {
            path = classURL.getPath();
        } else if ("jar".equalsIgnoreCase(classURL.getProtocol()) && classURL.getPath().startsWith("file:")) {
            // Strip the "file:" prefix and split at the "!"
            path = classURL.getPath().substring(5).split("!")[0];
        } else {
            return false;
        }

        // Abort if the class file isn't residing within the java build directory,
        // otherwise extract the prefix
        path = path.replace(File.separator, "/");
        int pos = path.lastIndexOf("/xtreemfs/java/servers/");
        if (pos < 0) {
            return false;
        }
        path = path.substring(0, pos);

        // Try to load the library from the build directory
        path = path + "/xtreemfs/cpp/build/" + filename;
        path = path.replace("/", File.separator);

        return tryLoadLibrary(path);
    }

    /**
     * Try to load the library from the lib directories defined in the Filesystem Hierarchy Standard.
     * 
     * @param filename
     * @return true if the library has been loaded
     */
    private static boolean tryLoadLibraryFromFHS(String filename) {
        if (tryLoadLibrary("/usr/lib64/xtreemfs/" + filename)) {
            return true;
        }

        if (tryLoadLibrary("/usr/lib/xtreemfs/" + filename)) {
            return true;
        }

        return false;
    }

    /**
     * Try to load the library from the path.
     * 
     * @param filepath
     *            Full absolute path to the library.
     * @return true If the library has been loaded
     */
    static boolean tryLoadLibrary(String filepath) {
        try {
            System.load(filepath);
        } catch (Exception e) {
            return false;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }

        return true;
    }

    public static ClientProxy createClientProxy(String[] dirServiceAddressesArray, UserCredentials userCredentials,
            SSLOptions sslOptions, Options options) {

        // TODO (jdillmann): Think about moving this to the factory.
        StringVector dirServiceAddressesVector = StringVector.from(Arrays.asList(dirServiceAddressesArray));
        ServiceAddresses dirServiceAddresses = new ServiceAddresses(dirServiceAddressesVector);
        OptionsProxy optionsProxy = NativeHelper.migrateOptions(options);
        SSLOptionsProxy sslOptionsProxy = null;
        if (sslOptions != null) {
            // TODO (jdillmann): Merge from sslOptions
            throw new RuntimeException("SSLOptions are not supported yet.");
        }

        return ClientProxy.createClient(dirServiceAddresses, userCredentials, sslOptionsProxy, optionsProxy);
    }

    public static OptionsProxy migrateOptions(Options o) {
        OptionsProxy op = new OptionsProxy();

        // Migrate the options, that are setable as Java Options
        op.setMetadata_cache_size(BigInteger.valueOf(o.getMetadataCacheSize()));
        op.setMax_tries(o.getMaxTries());
        op.setMax_read_tries(o.getMaxReadTries());
        op.setMax_view_renewals(o.getMaxViewRenewals());
        // TODO (jdillmann): check if maxWriteahed is in bytes or kb
        op.setAsync_writes_max_request_size_kb(o.getMaxWriteahead());
        op.setEnable_async_writes(o.getMaxWriteahead() > 0);
        op.setPeriodic_file_size_updates_interval_s(o.getPeriodicFileSizeUpdatesIntervalS());
        op.setReaddir_chunk_size(o.getReaddirChunkSize());

        // Migrate also Java defaults (TODO: ?)
        op.setConnect_timeout_s(o.getConnectTimeout_s());
        // o.getInterruptSignal()
        op.setLinger_timeout_s(o.getLingerTimeout_s());
        op.setAsync_writes_max_requests(o.getMaxWriteaheadRequests());
        op.setMetadata_cache_size(BigInteger.valueOf(o.getMetadataCacheSize()));
        op.setMetadata_cache_ttl_s(BigInteger.valueOf(o.getMetadataCacheTTLs()));
        op.setPeriodic_xcap_renewal_interval_s(o.getPeriodicXcapRenewalIntervalS());
        op.setRequest_timeout_s(o.getRequestTimeout_s());
        op.setRetry_delay_s(o.getRetryDelay_s());

        return op;
    }

    public static StringMap keyValueListToMap(List<KeyValuePair> list) {
        StringMap map = new StringMap();
        for (KeyValuePair kv : list) {
            map.set(kv.getKey(), kv.getValue());
        }
        return map;
    }
}