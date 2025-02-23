package com.linbit.linstor.dbdrivers;

import com.linbit.linstor.ControllerDatabase;
import com.linbit.linstor.core.objects.ExternalFileDbDriver;
import com.linbit.linstor.core.objects.KeyValueStoreDbDriver;
import com.linbit.linstor.core.objects.LayerBCacheRscDbDriver;
import com.linbit.linstor.core.objects.LayerBCacheVlmDbDriver;
import com.linbit.linstor.core.objects.LayerCacheRscDbDriver;
import com.linbit.linstor.core.objects.LayerCacheVlmDbDriver;
import com.linbit.linstor.core.objects.LayerDrbdRscDbDriver;
import com.linbit.linstor.core.objects.LayerDrbdRscDfnDbDriver;
import com.linbit.linstor.core.objects.LayerDrbdVlmDbDriver;
import com.linbit.linstor.core.objects.LayerDrbdVlmDfnDbDriver;
import com.linbit.linstor.core.objects.LayerLuksRscDbDriver;
import com.linbit.linstor.core.objects.LayerLuksVlmDbDriver;
import com.linbit.linstor.core.objects.LayerNvmeRscDbDriver;
import com.linbit.linstor.core.objects.LayerOpenflexRscDbDriver;
import com.linbit.linstor.core.objects.LayerOpenflexRscDfnDbDriver;
import com.linbit.linstor.core.objects.LayerOpenflexVlmDbDriver;
import com.linbit.linstor.core.objects.LayerResourceIdDbDriver;
import com.linbit.linstor.core.objects.LayerStorageRscDbDriver;
import com.linbit.linstor.core.objects.LayerStorageVlmDbDriver;
import com.linbit.linstor.core.objects.LayerWritecacheRscDbDriver;
import com.linbit.linstor.core.objects.LayerWritecacheVlmDbDriver;
import com.linbit.linstor.core.objects.NetInterfaceDbDriver;
import com.linbit.linstor.core.objects.NodeConnectionDbDriver;
import com.linbit.linstor.core.objects.NodeDbDriver;
import com.linbit.linstor.core.objects.ResourceConnectionDbDriver;
import com.linbit.linstor.core.objects.ResourceDbDriver;
import com.linbit.linstor.core.objects.ResourceDefinitionDbDriver;
import com.linbit.linstor.core.objects.ResourceGroupDbDriver;
import com.linbit.linstor.core.objects.ScheduleDbDriver;
import com.linbit.linstor.core.objects.SnapshotDbDriver;
import com.linbit.linstor.core.objects.SnapshotDefinitionDbDriver;
import com.linbit.linstor.core.objects.SnapshotVolumeDbDriver;
import com.linbit.linstor.core.objects.SnapshotVolumeDefinitionDbDriver;
import com.linbit.linstor.core.objects.StorPoolDbDriver;
import com.linbit.linstor.core.objects.StorPoolDefinitionDbDriver;
import com.linbit.linstor.core.objects.VolumeConnectionDbDriver;
import com.linbit.linstor.core.objects.VolumeDbDriver;
import com.linbit.linstor.core.objects.VolumeDefinitionDbDriver;
import com.linbit.linstor.core.objects.VolumeGroupDbDriver;
import com.linbit.linstor.core.objects.remotes.EbsRemoteDbDriver;
import com.linbit.linstor.core.objects.remotes.LinstorRemoteDbDriver;
import com.linbit.linstor.core.objects.remotes.S3RemoteDbDriver;
import com.linbit.linstor.dbcp.DbConnectionPoolInitializer;
import com.linbit.linstor.dbcp.DbInitializer;
import com.linbit.linstor.dbdrivers.interfaces.ExternalFileCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ExternalFileDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.KeyValueStoreCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.KeyValueStoreDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerBCacheRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerBCacheRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerBCacheVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerCacheRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerCacheRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerCacheVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerDrbdRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerDrbdRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerDrbdRscDfnDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerDrbdVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerDrbdVlmDfnDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerLuksRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerLuksRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerLuksVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerNvmeRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerNvmeRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerOpenflexRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerOpenflexRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerOpenflexRscDfnDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerOpenflexVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerResourceIdCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerResourceIdDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerStorageRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerStorageRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerStorageVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerWritecacheRscCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerWritecacheRscDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.LayerWritecacheVlmDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.NetInterfaceCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.NetInterfaceDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.NodeConnectionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.NodeConnectionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.NodeCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.NodeDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.PropsCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.PropsDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceConnectionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceConnectionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceDefinitionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceDefinitionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceGroupCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ResourceGroupDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ScheduleCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.ScheduleDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecConfigCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecConfigDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecDefaultRoleCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecDefaultRoleDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecIdRoleCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecIdRoleDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecIdentityCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecIdentityDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecObjProtAclCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecObjProtAclDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecObjProtCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecObjProtDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecRoleCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecRoleDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecTypeCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecTypeDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecTypeRulesCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SecTypeRulesDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotDefinitionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotDefinitionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotVolumeCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotVolumeDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotVolumeDefinitionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.SnapshotVolumeDefinitionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.StorPoolCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.StorPoolDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.StorPoolDefinitionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.StorPoolDefinitionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeConnectionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeConnectionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeDefinitionCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeDefinitionDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeGroupCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.VolumeGroupDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.remotes.EbsRemoteCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.remotes.EbsRemoteDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.remotes.LinstorRemoteCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.remotes.LinstorRemoteDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.remotes.S3RemoteCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.remotes.S3RemoteDatabaseDriver;
import com.linbit.linstor.dbdrivers.sql.SQLEngine;
import com.linbit.linstor.security.DbAccessor;
import com.linbit.linstor.security.DbSQLPersistence;
import com.linbit.linstor.security.SecConfigDbDriver;
import com.linbit.linstor.security.SecDefaultRoleDbDriver;
import com.linbit.linstor.security.SecIdRoleDbDriver;
import com.linbit.linstor.security.SecIdentityDbDriver;
import com.linbit.linstor.security.SecObjectProtectionAclDbDriver;
import com.linbit.linstor.security.SecObjectProtectionDbDriver;
import com.linbit.linstor.security.SecRoleDbDriver;
import com.linbit.linstor.security.SecTypeDbDriver;
import com.linbit.linstor.security.SecTypeRulesDbDriver;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;

import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.MapBinder;

public class TestDbModule extends AbstractModule
{
    @Override
    protected void configure()
    {
        bind(DbEngine.class).to(SQLEngine.class);

        bind(SecConfigCtrlDatabaseDriver.class).to(SecConfigDbDriver.class);
        bind(SecConfigDatabaseDriver.class).to(SecConfigDbDriver.class);
        bind(SecDefaultRoleCtrlDatabaseDriver.class).to(SecDefaultRoleDbDriver.class);
        bind(SecDefaultRoleDatabaseDriver.class).to(SecDefaultRoleDbDriver.class);
        bind(SecIdentityCtrlDatabaseDriver.class).to(SecIdentityDbDriver.class);
        bind(SecIdentityDatabaseDriver.class).to(SecIdentityDbDriver.class);
        bind(SecIdRoleCtrlDatabaseDriver.class).to(SecIdRoleDbDriver.class);
        bind(SecIdRoleDatabaseDriver.class).to(SecIdRoleDbDriver.class);
        bind(SecObjProtAclCtrlDatabaseDriver.class).to(SecObjectProtectionAclDbDriver.class);
        bind(SecObjProtAclDatabaseDriver.class).to(SecObjectProtectionAclDbDriver.class);
        bind(SecObjProtCtrlDatabaseDriver.class).to(SecObjectProtectionDbDriver.class);
        bind(SecObjProtDatabaseDriver.class).to(SecObjectProtectionDbDriver.class);
        bind(SecRoleCtrlDatabaseDriver.class).to(SecRoleDbDriver.class);
        bind(SecRoleDatabaseDriver.class).to(SecRoleDbDriver.class);
        bind(SecTypeCtrlDatabaseDriver.class).to(SecTypeDbDriver.class);
        bind(SecTypeDatabaseDriver.class).to(SecTypeDbDriver.class);
        bind(SecTypeRulesCtrlDatabaseDriver.class).to(SecTypeRulesDbDriver.class);
        bind(SecTypeRulesDatabaseDriver.class).to(SecTypeRulesDbDriver.class);

        bind(DatabaseDriver.class).to(DatabaseLoader.class);
        // bind(DatabaseDriver.class).to(TestDatabaseLoader.class);

        bind(PropsDatabaseDriver.class).to(PropsDbDriver.class);
        bind(PropsCtrlDatabaseDriver.class).to(PropsDbDriver.class);

        bind(ResourceDefinitionCtrlDatabaseDriver.class).to(ResourceDefinitionDbDriver.class);
        bind(ResourceDefinitionDatabaseDriver.class).to(ResourceDefinitionDbDriver.class);
        bind(ResourceGroupCtrlDatabaseDriver.class).to(ResourceGroupDbDriver.class);
        bind(ResourceGroupDatabaseDriver.class).to(ResourceGroupDbDriver.class);
        bind(VolumeGroupCtrlDatabaseDriver.class).to(VolumeGroupDbDriver.class);
        bind(VolumeGroupDatabaseDriver.class).to(VolumeGroupDbDriver.class);
        bind(VolumeCtrlDatabaseDriver.class).to(VolumeDbDriver.class);
        bind(VolumeDatabaseDriver.class).to(VolumeDbDriver.class);
        bind(ResourceCtrlDatabaseDriver.class).to(ResourceDbDriver.class);
        bind(ResourceDatabaseDriver.class).to(ResourceDbDriver.class);
        bind(VolumeDefinitionCtrlDatabaseDriver.class).to(VolumeDefinitionDbDriver.class);
        bind(VolumeDefinitionDatabaseDriver.class).to(VolumeDefinitionDbDriver.class);
        bind(StorPoolDefinitionCtrlDatabaseDriver.class).to(StorPoolDefinitionDbDriver.class);
        bind(StorPoolDefinitionDatabaseDriver.class).to(StorPoolDefinitionDbDriver.class);
        bind(StorPoolCtrlDatabaseDriver.class).to(StorPoolDbDriver.class);
        bind(StorPoolDatabaseDriver.class).to(StorPoolDbDriver.class);
        bind(NodeCtrlDatabaseDriver.class).to(NodeDbDriver.class);
        bind(NodeDatabaseDriver.class).to(NodeDbDriver.class);
        bind(NetInterfaceCtrlDatabaseDriver.class).to(NetInterfaceDbDriver.class);
        bind(NetInterfaceDatabaseDriver.class).to(NetInterfaceDbDriver.class);
        bind(NodeConnectionCtrlDatabaseDriver.class).to(NodeConnectionDbDriver.class);
        bind(NodeConnectionDatabaseDriver.class).to(NodeConnectionDbDriver.class);
        bind(ResourceConnectionCtrlDatabaseDriver.class).to(ResourceConnectionDbDriver.class);
        bind(ResourceConnectionDatabaseDriver.class).to(ResourceConnectionDbDriver.class);
        bind(VolumeConnectionCtrlDatabaseDriver.class).to(VolumeConnectionDbDriver.class);
        bind(VolumeConnectionDatabaseDriver.class).to(VolumeConnectionDbDriver.class);
        bind(SnapshotDefinitionCtrlDatabaseDriver.class).to(SnapshotDefinitionDbDriver.class);
        bind(SnapshotDefinitionDatabaseDriver.class).to(SnapshotDefinitionDbDriver.class);
        bind(SnapshotVolumeDefinitionCtrlDatabaseDriver.class).to(SnapshotVolumeDefinitionDbDriver.class);
        bind(SnapshotVolumeDefinitionDatabaseDriver.class).to(SnapshotVolumeDefinitionDbDriver.class);
        bind(SnapshotCtrlDatabaseDriver.class).to(SnapshotDbDriver.class);
        bind(SnapshotDatabaseDriver.class).to(SnapshotDbDriver.class);
        bind(SnapshotVolumeCtrlDatabaseDriver.class).to(SnapshotVolumeDbDriver.class);
        bind(SnapshotVolumeDatabaseDriver.class).to(SnapshotVolumeDbDriver.class);
        bind(KeyValueStoreCtrlDatabaseDriver.class).to(KeyValueStoreDbDriver.class);
        bind(KeyValueStoreDatabaseDriver.class).to(KeyValueStoreDbDriver.class);
        bind(ExternalFileCtrlDatabaseDriver.class).to(ExternalFileDbDriver.class);
        bind(ExternalFileDatabaseDriver.class).to(ExternalFileDbDriver.class);
        bind(S3RemoteCtrlDatabaseDriver.class).to(S3RemoteDbDriver.class);
        bind(S3RemoteDatabaseDriver.class).to(S3RemoteDbDriver.class);
        bind(LinstorRemoteCtrlDatabaseDriver.class).to(LinstorRemoteDbDriver.class);
        bind(LinstorRemoteDatabaseDriver.class).to(LinstorRemoteDbDriver.class);
        bind(EbsRemoteCtrlDatabaseDriver.class).to(EbsRemoteDbDriver.class);
        bind(EbsRemoteDatabaseDriver.class).to(EbsRemoteDbDriver.class);
        bind(ScheduleCtrlDatabaseDriver.class).to(ScheduleDbDriver.class);
        bind(ScheduleDatabaseDriver.class).to(ScheduleDbDriver.class);

        bind(KeyValueStoreCtrlDatabaseDriver.class).to(KeyValueStoreDbDriver.class);
        bind(KeyValueStoreDatabaseDriver.class).to(KeyValueStoreDbDriver.class);
        bind(ExternalFileCtrlDatabaseDriver.class).to(ExternalFileDbDriver.class);
        bind(ExternalFileDatabaseDriver.class).to(ExternalFileDbDriver.class);

        bind(LayerResourceIdCtrlDatabaseDriver.class).to(LayerResourceIdDbDriver.class);
        bind(LayerResourceIdDatabaseDriver.class).to(LayerResourceIdDbDriver.class);

        MapBinder<DeviceLayerKind, ControllerLayerRscDatabaseDriver> layerRscDatabaseDrivers;
        layerRscDatabaseDrivers = MapBinder.newMapBinder(
            binder(),
            DeviceLayerKind.class,
            ControllerLayerRscDatabaseDriver.class
        );

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.DRBD).to(LayerDrbdRscDbDriver.class);
        bind(LayerDrbdRscCtrlDatabaseDriver.class).to(LayerDrbdRscDbDriver.class);
        bind(LayerDrbdRscDfnDatabaseDriver.class).to(LayerDrbdRscDfnDbDriver.class);
        bind(LayerDrbdVlmDfnDatabaseDriver.class).to(LayerDrbdVlmDfnDbDriver.class);
        bind(LayerDrbdRscDatabaseDriver.class).to(LayerDrbdRscDbDriver.class);
        bind(LayerDrbdVlmDatabaseDriver.class).to(LayerDrbdVlmDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.STORAGE).to(LayerStorageRscDbDriver.class);
        bind(LayerStorageRscCtrlDatabaseDriver.class).to(LayerStorageRscDbDriver.class);
        bind(LayerStorageRscDatabaseDriver.class).to(LayerStorageRscDbDriver.class);
        bind(LayerStorageVlmDatabaseDriver.class).to(LayerStorageVlmDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.LUKS).to(LayerLuksRscDbDriver.class);
        bind(LayerLuksRscCtrlDatabaseDriver.class).to(LayerLuksRscDbDriver.class);
        bind(LayerLuksRscDatabaseDriver.class).to(LayerLuksRscDbDriver.class);
        bind(LayerLuksVlmDatabaseDriver.class).to(LayerLuksVlmDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.NVME).to(LayerNvmeRscDbDriver.class);
        bind(LayerNvmeRscCtrlDatabaseDriver.class).to(LayerNvmeRscDbDriver.class);
        bind(LayerNvmeRscDatabaseDriver.class).to(LayerNvmeRscDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.OPENFLEX).to(LayerOpenflexRscDbDriver.class);
        bind(LayerOpenflexRscCtrlDatabaseDriver.class).to(LayerOpenflexRscDbDriver.class);
        bind(LayerOpenflexRscDfnDatabaseDriver.class).to(LayerOpenflexRscDfnDbDriver.class);
        bind(LayerOpenflexRscDatabaseDriver.class).to(LayerOpenflexRscDbDriver.class);
        bind(LayerOpenflexVlmDatabaseDriver.class).to(LayerOpenflexVlmDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.WRITECACHE).to(LayerWritecacheRscDbDriver.class);
        bind(LayerWritecacheRscCtrlDatabaseDriver.class).to(LayerWritecacheRscDbDriver.class);
        bind(LayerWritecacheRscDatabaseDriver.class).to(LayerWritecacheRscDbDriver.class);
        bind(LayerWritecacheVlmDatabaseDriver.class).to(LayerWritecacheVlmDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.CACHE).to(LayerCacheRscDbDriver.class);
        bind(LayerCacheRscCtrlDatabaseDriver.class).to(LayerCacheRscDbDriver.class);
        bind(LayerCacheRscDatabaseDriver.class).to(LayerCacheRscDbDriver.class);
        bind(LayerCacheVlmDatabaseDriver.class).to(LayerCacheVlmDbDriver.class);

        layerRscDatabaseDrivers.addBinding(DeviceLayerKind.BCACHE).to(LayerBCacheRscDbDriver.class);
        bind(LayerBCacheRscCtrlDatabaseDriver.class).to(LayerBCacheRscDbDriver.class);
        bind(LayerBCacheRscDatabaseDriver.class).to(LayerBCacheRscDbDriver.class);
        bind(LayerBCacheVlmDatabaseDriver.class).to(LayerBCacheVlmDbDriver.class);

        bind(DbInitializer.class).to(DbConnectionPoolInitializer.class);
        bind(new TypeLiteral<DbAccessor<? extends ControllerDatabase>>()
        {
        }).to(new TypeLiteral<DbSQLPersistence>()
        {
        });
    }
}
