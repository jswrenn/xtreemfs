/*
 * Copyright (c) 2009-2011 by Bjoern Kolbeck,
 *               Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.osd.operations;

import java.io.IOException;
import java.util.List;
import org.xtreemfs.common.Capability;
import org.xtreemfs.common.stage.AbstractRPCRequestCallback;
import org.xtreemfs.common.stage.RPCRequestCallback;
import org.xtreemfs.common.stage.StageRequest;
import org.xtreemfs.common.uuids.ServiceUUID;
import org.xtreemfs.common.xloc.InvalidXLocationsException;
import org.xtreemfs.common.xloc.XLocations;
import org.xtreemfs.foundation.buffer.BufferPool;
import org.xtreemfs.foundation.pbrpc.client.RPCAuthentication;
import org.xtreemfs.foundation.pbrpc.client.RPCResponse;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.ErrorType;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.POSIXErrno;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.RPCHeader.ErrorResponse;
import org.xtreemfs.foundation.pbrpc.utils.ErrorUtils;
import org.xtreemfs.foundation.pbrpc.utils.ErrorUtils.ErrorResponseException;
import org.xtreemfs.osd.InternalObjectData;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.storage.ObjectInformation;
import org.xtreemfs.osd.storage.StorageLayout;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.SnapConfig;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.InternalGmax;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.xtreemfs_check_objectRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSDServiceConstants;

public final class CheckObjectOperation extends OSDOperation {

    private final String sharedSecret;
    private final ServiceUUID localUUID;

    public CheckObjectOperation(OSDRequestDispatcher master) {
        super(master);
        
        sharedSecret = master.getConfig().getCapabilitySecret();
        localUUID = master.getConfig().getUUID();
    }

    @Override
    public int getProcedureId() {
        
        return OSDServiceConstants.PROC_ID_XTREEMFS_CHECK_OBJECT;
    }

    @Override
    public ErrorResponse startRequest(OSDRequest rq, final RPCRequestCallback callback) {
        
        final xtreemfs_check_objectRequest args = (xtreemfs_check_objectRequest) rq.getRequestArgs();

        if (args.getObjectNumber() < 0) {
            
            return ErrorUtils.getErrorResponse(ErrorType.ERRNO, POSIXErrno.POSIX_ERROR_EINVAL, 
                    "object number must be >= 0");
        }
        
        master.getStorageStage().readObject(args.getObjectNumber(), 
                rq.getLocationList().getLocalReplica().getStripingPolicy(), 0,StorageLayout.FULL_OBJECT_LENGTH, 
                rq.getCapability().getSnapConfig() == SnapConfig.SNAP_CONFIG_ACCESS_SNAP ? rq.getCapability()
                    .getSnapTimestamp() : 0, rq, new AbstractRPCRequestCallback(callback) {
                        
                        @Override
                        public <S extends StageRequest<?>> boolean success(Object result, S stageRequest)
                                throws ErrorResponseException {
                            
                            final OSDRequest rq = (OSDRequest) stageRequest.getRequest();
                            if (rq.getLocationList().getLocalReplica().getOSDs().size() == 1) {
                                
                                //non-striped case
                                return nonStripedCheckObject(args, (ObjectInformation) result, callback);
                            } else {
                                
                                //striped read
                                return stripedCheckObject(rq, args, (ObjectInformation) result, callback);
                            }
                        }
                    });
        
        return null;
    }

    private boolean nonStripedCheckObject(xtreemfs_check_objectRequest args, ObjectInformation result, 
            RPCRequestCallback callback) throws ErrorResponseException {

        boolean isLastObjectOrEOF = result.getLastLocalObjectNo() <= args.getObjectNumber();
        return readFinish(result, isLastObjectOrEOF, callback);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean stripedCheckObject(final OSDRequest rq, final xtreemfs_check_objectRequest args, 
            final ObjectInformation result, final RPCRequestCallback callback) throws ErrorResponseException {
        
        //ObjectData data;
        long objNo = args.getObjectNumber();
        long lastKnownObject = Math.max(result.getLastLocalObjectNo(), result.getGlobalLastObjectNo());
        boolean isLastObjectLocallyKnown = lastKnownObject <= objNo;
        
        //check if GMAX must be fetched to determin EOF
        if ((objNo > lastKnownObject) ||
            (objNo == lastKnownObject) && 
            (result.getData() != null) && 
            (result.getData().remaining() < result.getStripeSize())) {
            
            try {
                List<ServiceUUID> osds = rq.getLocationList().getLocalReplica().getOSDs();
                final RPCResponse[] gmaxRPCs = new RPCResponse[osds.size() - 1];
                int cnt = 0;
                for (ServiceUUID osd : osds) {
                    if (!osd.equals(localUUID)) {
                        gmaxRPCs[cnt++] = master.getOSDClient().xtreemfs_internal_get_gmax(osd.getAddress(), RPCAuthentication.authNone, RPCAuthentication.userService, args.getFileCredentials(), args.getFileId());
                    }
                }
                waitForResponses(gmaxRPCs, new ResponsesListener() {

                    // executed by the OSDClient
                    @Override
                    public void responsesAvailable() {
                        
                        try {
                            
                            stripedCheckObjectAnalyzeGmax(args, result, gmaxRPCs, callback);
                        } catch (ErrorResponseException e) {
                            
                            callback.failed(e.getRPCError());
                        }
                    }
                });
            } catch (IOException ex) {
                
                throw new ErrorResponseException(ex);
            }
        } else {
            
            return readFinish(result, isLastObjectLocallyKnown, callback);
        }
        
        return true;
    }

    @SuppressWarnings("rawtypes")
    private void stripedCheckObjectAnalyzeGmax(xtreemfs_check_objectRequest args, ObjectInformation result, 
            RPCResponse[] gmaxRPCs, RPCRequestCallback callback) throws ErrorResponseException {
        
        long maxObjNo = -1;
        long maxTruncate = -1;

        try {
            
            for (int i = 0; i < gmaxRPCs.length; i++) {
                InternalGmax gmax = (InternalGmax) gmaxRPCs[i].get();
                if ((gmax.getLastObjectId() > maxObjNo) && (gmax.getEpoch() >= maxTruncate)) {
                    //found new max
                    maxObjNo = gmax.getLastObjectId();
                    maxTruncate = gmax.getEpoch();
                }
            }
            boolean isLastObjectLocallyKnown = maxObjNo <= args.getObjectNumber();
            readFinish(result, isLastObjectLocallyKnown, callback);
            
            //and update gmax locally
            master.getStorageStage().receivedGMAX_ASYNC(args.getFileId(), maxTruncate, maxObjNo);
        } catch (ErrorResponseException e) {
            
            throw e;
        } catch (Exception e) {
            
            throw new ErrorResponseException(e);
        } finally {
            
            for (RPCResponse r : gmaxRPCs) {
                r.freeBuffers();
            }
        }
    }

    private boolean readFinish(ObjectInformation result, boolean isLastObjectOrEOF, RPCRequestCallback callback) 
            throws ErrorResponseException {

        InternalObjectData data;
        data = result.getObjectData(isLastObjectOrEOF, 0, result.getStripeSize());
        if (data.getData() != null) {
            data.setZero_padding(data.getZero_padding() + data.getData().remaining());
            BufferPool.free(data.getData());
            data.setData(null);
        }
        return callback.success(data.getMetadata());
    }

    @Override
    public ErrorResponse parseRPCMessage(OSDRequest rq) {
        
        try {
            xtreemfs_check_objectRequest rpcrq = (xtreemfs_check_objectRequest) rq.getRequestArgs();
            rq.setFileId(rpcrq.getFileId());
            rq.setCapability(new Capability(rpcrq.getFileCredentials().getXcap(), sharedSecret));
            rq.setLocationList(new XLocations(rpcrq.getFileCredentials().getXlocs(), localUUID));

            return null;
        } catch (InvalidXLocationsException ex) {
            return ErrorUtils.getErrorResponse(ErrorType.ERRNO, POSIXErrno.POSIX_ERROR_EINVAL, ex.toString());
        } catch (Throwable ex) {
            return ErrorUtils.getInternalServerError(ex);
        }
    }

    @Override
    public boolean requiresCapability() {
        
        return true;
    }

    @Override
    public void startInternalEvent(Object[] args) {
        
        throw new UnsupportedOperationException("Not supported yet.");
    }
}