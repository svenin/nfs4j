/*
 * Copyright (c) 2015 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.nfs.v4;

import java.io.IOException;
import java.net.InetSocketAddress;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.status.ServerFaultException;
import org.dcache.nfs.v4.ff.ff_data_server4;
import org.dcache.nfs.v4.ff.ff_device_addr4;
import org.dcache.nfs.v4.ff.ff_device_versions4;
import org.dcache.nfs.v4.ff.ff_layout4;
import org.dcache.nfs.v4.ff.ff_mirror4;
import org.dcache.nfs.v4.ff.flex_files_prot;
import org.dcache.nfs.v4.xdr.device_addr4;
import org.dcache.nfs.v4.xdr.deviceid4;
import org.dcache.nfs.v4.xdr.fattr4_owner;
import org.dcache.nfs.v4.xdr.fattr4_owner_group;
import org.dcache.nfs.v4.xdr.layout_content4;
import org.dcache.nfs.v4.xdr.layouttype4;
import org.dcache.nfs.v4.xdr.length4;
import org.dcache.nfs.v4.xdr.multipath_list4;
import org.dcache.nfs.v4.xdr.netaddr4;
import org.dcache.nfs.v4.xdr.nfs_fh4;
import org.dcache.nfs.v4.xdr.stateid4;
import org.dcache.nfs.v4.xdr.uint32_t;
import org.dcache.nfs.v4.xdr.utf8str_mixed;
import org.dcache.xdr.OncRpcException;
import org.dcache.xdr.XdrBuffer;
import org.glassfish.grizzly.Buffer;

/**
 * layout driver for Flexible File layout type as defined in
 * <a href="https://tools.ietf.org/id/draft-ietf-nfsv4-flex-files-06.txt">flex-files draft6</a>
 */
public class FlexFileLayoutDriver implements LayoutDriver {

    private final static utf8str_mixed NO_PRINCIPAL = new utf8str_mixed("");

    @Override
    public int getLayoutType() {
        return layouttype4.LAYOUT4_FLEX_FILES;
    }

    @Override
    public device_addr4 getDeviceAddress(InetSocketAddress... deviceAddress) throws ChimeraNFSException {
        ff_device_addr4 flexfile_type = new ff_device_addr4();

        flexfile_type.ffda_versions = new ff_device_versions4[1];
        flexfile_type.ffda_versions[0] = new ff_device_versions4();
        flexfile_type.ffda_versions[0].ffdv_version = new uint32_t(4);
        flexfile_type.ffda_versions[0].ffdv_minorversion = new uint32_t(0);
        flexfile_type.ffda_versions[0].ffdv_rsize = new uint32_t(64 * 1024);
        flexfile_type.ffda_versions[0].ffdv_wsize = new uint32_t(64 * 1024);
        flexfile_type.ffda_versions[0].ffdv_tightly_coupled = true;

        flexfile_type.ffda_netaddrs = new multipath_list4();
        flexfile_type.ffda_netaddrs.value = new netaddr4[deviceAddress.length];
        for (int i = 0; i < deviceAddress.length; i++) {
            flexfile_type.ffda_netaddrs.value[i] = new netaddr4(deviceAddress[i]);
        }

        XdrBuffer xdr = new XdrBuffer(128);
        try {
            xdr.beginEncoding();
            flexfile_type.xdrEncode(xdr);
            xdr.endEncoding();
        } catch (OncRpcException e) {
            /* forced by interface, should never happen. */
            throw new RuntimeException("Unexpected OncRpcException:" + e.getMessage(), e);
        } catch (IOException e) {
            /* forced by interface, should never happen. */
            throw new RuntimeException("Unexpected IOException:"  + e.getMessage(), e);
        }

        Buffer body = xdr.asBuffer();
        byte[] retBytes = new byte[body.remaining()];
        body.get(retBytes);

        device_addr4 addr = new device_addr4();
        addr.da_layout_type = layouttype4.LAYOUT4_FLEX_FILES;
        addr.da_addr_body = retBytes;

        return addr;
    }

    @Override
    public layout_content4 getLayoutContent(deviceid4 deviceid, stateid4 stateid, int stripeSize, nfs_fh4 fh) throws ChimeraNFSException {
        ff_layout4 layout = new ff_layout4();

        layout.ffl_stripe_unit = new length4(0);
        layout.ffl_mirrors = new ff_mirror4[1];
        layout.ffl_mirrors[0] = createNewMirror(deviceid, 0, stateid, fh);
        layout.ffl_flags4 = new uint32_t(flex_files_prot.FF_FLAGS_NO_LAYOUTCOMMIT);

        XdrBuffer xdr = new XdrBuffer(512);
        xdr.beginEncoding();

        try {
            layout.xdrEncode(xdr);
        } catch (IOException e) {
            throw new ServerFaultException("failed to encode layout body", e);
        }
        xdr.endEncoding();

        Buffer xdrBody = xdr.asBuffer();
        byte[] body = new byte[xdrBody.remaining()];
        xdrBody.get(body);

        layout_content4 content = new layout_content4();
        content.loc_type = layouttype4.LAYOUT4_FLEX_FILES;
        content.loc_body = body;
        return content;
    }

    private static ff_data_server4 createDataserver(deviceid4 deviceid,
            int efficiency, stateid4 stateid, nfs_fh4 fileHandle) {
        ff_data_server4 ds = new ff_data_server4();
        ds.ffds_deviceid = deviceid;
        ds.ffds_efficiency = new uint32_t(efficiency);
        ds.ffds_stateid = stateid;
        ds.ffds_fh_vers = new nfs_fh4[]{fileHandle};
        ds.ffds_user = new fattr4_owner(NO_PRINCIPAL);
        ds.ffds_group = new fattr4_owner_group(NO_PRINCIPAL);
        return ds;
    }

    private static ff_mirror4 createNewMirror(deviceid4 deviceid, int efficiency, stateid4 stateid, nfs_fh4 fileHandle) {
        ff_mirror4 mirror = new ff_mirror4();
        mirror.ffm_data_servers = new ff_data_server4[1];
        mirror.ffm_data_servers[0] = createDataserver(deviceid, efficiency, stateid, fileHandle);
        return mirror;
    }

}
