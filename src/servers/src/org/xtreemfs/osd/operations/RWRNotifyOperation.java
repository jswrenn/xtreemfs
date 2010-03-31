/*  Copyright (c) 2010 Konrad-Zuse-Zentrum fuer Informationstechnik Berlin.

This file is part of XtreemFS. XtreemFS is part of XtreemOS, a Linux-based
Grid Operating System, see <http://www.xtreemos.eu> for more details.
The XtreemOS project has been developed with the financial support of the
European Commission's IST program under contract #FP6-033576.

XtreemFS is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free
Software Foundation, either version 2 of the License, or (at your option)
any later version.

XtreemFS is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with XtreemFS. If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * AUTHORS: Björn Kolbeck (ZIB)
 */

package org.xtreemfs.osd.operations;

import org.xtreemfs.common.Capability;
import org.xtreemfs.common.uuids.ServiceUUID;
import org.xtreemfs.common.xloc.XLocations;
import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.oncrpc.utils.XDRUnmarshaller;
import org.xtreemfs.interfaces.OSDInterface.RedirectException;
import org.xtreemfs.interfaces.OSDInterface.xtreemfs_rwr_notifyRequest;
import org.xtreemfs.interfaces.OSDInterface.xtreemfs_rwr_notifyResponse;
import org.xtreemfs.interfaces.OSDInterface.xtreemfs_rwr_updateRequest;
import org.xtreemfs.interfaces.OSDInterface.xtreemfs_rwr_updateResponse;
import org.xtreemfs.interfaces.OSDWriteResponse;
import org.xtreemfs.interfaces.utils.ONCRPCException;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.rwre.RWReplicationStage;
import org.xtreemfs.osd.stages.PreprocStage.DeleteOnCloseCallback;
import org.xtreemfs.osd.stages.StorageStage.InternalGetMaxObjectNoCallback;
import org.xtreemfs.osd.stages.StorageStage.WriteObjectCallback;
import org.xtreemfs.osd.storage.CowPolicy;

public final class RWRNotifyOperation extends OSDOperation {

    final int procId;
    final String sharedSecret;
    final ServiceUUID localUUID;

    public RWRNotifyOperation(OSDRequestDispatcher master) {
        super(master);
        procId = xtreemfs_rwr_notifyRequest.TAG;
        sharedSecret = master.getConfig().getCapabilitySecret();
        localUUID = master.getConfig().getUUID();
    }

    @Override
    public int getProcedureId() {
        return procId;
    }

    @Override
    public void startRequest(final OSDRequest rq) {
        final xtreemfs_rwr_notifyRequest args = (xtreemfs_rwr_notifyRequest)rq.getRequestArgs();

        

        if (!rq.getLocationList().containsOSD(localUUID)) {
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, this,"RWR notify for file %s: REMOVE REPLICA",args.getFile_id());
            }
            master.getPreprocStage().checkDeleteOnClose(args.getFile_id(), new DeleteOnCloseCallback() {

                @Override
                public void deleteOnCloseResult(boolean isDeleteOnClose, Exception error) {
                    sendResult(rq, error);
                }
            });
        } else {
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, this,"RWR notify for file %s: FORCE RESET",args.getFile_id());
            }
            master.getRWReplicationStage().eventForceReset(args.getFile_credentials(), rq.getLocationList());
            sendResult(rq, null);
        }
    }
    


    public void sendResult(final OSDRequest rq, Exception error) {

        if (error != null) {
            if (error instanceof ONCRPCException)
                rq.sendException((ONCRPCException)error);
            else
                rq.sendInternalServerError(error);
        } else {
            //only locally
            sendResponse(rq);
        }
    }

    
    public void sendResponse(OSDRequest rq) {
        xtreemfs_rwr_notifyResponse response = new xtreemfs_rwr_notifyResponse();
        rq.sendSuccess(response);
    }

    @Override
    public yidl.runtime.Object parseRPCMessage(ReusableBuffer data, OSDRequest rq) throws Exception {
        xtreemfs_rwr_notifyRequest rpcrq = new xtreemfs_rwr_notifyRequest();
        rpcrq.unmarshal(new XDRUnmarshaller(data));

        rq.setFileId(rpcrq.getFile_id());
        rq.setCapability(new Capability(rpcrq.getFile_credentials().getXcap(),sharedSecret));
        rq.setLocationList(new XLocations(rpcrq.getFile_credentials().getXlocs(), localUUID));

        return rpcrq;
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