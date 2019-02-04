package com.linbit.linstor.core.devmgr;

import com.linbit.ImplementationError;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.Node;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.Snapshot;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.Volume;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.Resource.RscFlags;
import com.linbit.linstor.Snapshot.SnapshotFlags;
import com.linbit.linstor.annotation.DeviceManagerContext;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.SpaceInfo;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.core.ControllerPeerConnector;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.devmgr.helper.LayeredResourcesHelper;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.LayerFactory;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.interfaces.categories.RscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.VlmProviderObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.layer.DeviceLayer.NotificationListener;
import com.linbit.linstor.storage.layer.DeviceLayer;
import com.linbit.linstor.storage.layer.exceptions.ResourceException;
import com.linbit.linstor.storage.layer.exceptions.VolumeException;
import com.linbit.linstor.storage.layer.provider.StorageLayer;
import com.linbit.utils.Either;
import com.linbit.utils.RemoveAfterDevMgrRework;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class DeviceHandlerImpl implements DeviceHandler
{
    private final AccessContext wrkCtx;
    private final ErrorReporter errorReporter;
    private final Provider<NotificationListener> notificationListener;

    private final ControllerPeerConnector controllerPeerConnector;
    private final CtrlStltSerializer interComSerializer;

    private final LayeredResourcesHelper layeredRscHelper;
    private final LayerFactory layerFactory;
    private final AtomicBoolean fullSyncApplied;
    private final StorageLayer storageLayer;

    @Inject
    public DeviceHandlerImpl(
        @DeviceManagerContext AccessContext wrkCtxRef,
        ErrorReporter errorReporterRef,
        ControllerPeerConnector controllerPeerConnectorRef,
        CtrlStltSerializer interComSerializerRef,
        Provider<NotificationListener> notificationListenerRef,
        LayeredResourcesHelper layeredRscHelperRef,
        LayerFactory layerFactoryRef,
        StorageLayer storageLayerRef
    )
    {
        wrkCtx = wrkCtxRef;
        errorReporter = errorReporterRef;
        controllerPeerConnector = controllerPeerConnectorRef;
        interComSerializer = interComSerializerRef;
        notificationListener = notificationListenerRef;

        layeredRscHelper = layeredRscHelperRef;
        layerFactory = layerFactoryRef;
        storageLayer = storageLayerRef;

        fullSyncApplied = new AtomicBoolean(false);
    }

    @Override
    public void dispatchResources(Collection<Resource> rscs, Collection<Snapshot> snapshots)
    {
        /*
         * TODO: we may need to add some snapshotname-suffix logic (like for resourcename)
         * when implementing snapshots for / through RAID-layer
         */

        Collection<Resource> origResources = rscs;
        Collection<Resource> allResources = convertResources(origResources, snapshots);

        Map<DeviceLayer, Set<RscLayerObject>> rscByLayer = groupResourcesByLayer(allResources);
        Map<ResourceName, Set<Snapshot>> snapshotsByRscName = groupSnapshotsByResourceName(snapshots);

        calculateGrossSizes(rscs);

        boolean prepareSuccess = prepareLayers(rscByLayer, snapshotsByRscName);

        if (prepareSuccess)
        {
            List<Snapshot> unprocessedSnapshots = new ArrayList<>(snapshots);

            List<Resource> rscListNotifyApplied = new ArrayList<>();
            List<Resource> rscListNotifyDelete = new ArrayList<>();
            List<Snapshot> snapListNotifyDelete = new ArrayList<>();

            processResourcesAndTheirSnapshots(
                snapshots,
                rscs,
                snapshotsByRscName,
                unprocessedSnapshots,
                rscListNotifyApplied,
                rscListNotifyDelete,
                snapListNotifyDelete
            );
            processUnprocessedSnapshots(unprocessedSnapshots);

            notifyResourcesApplied(rscListNotifyApplied);

            clearLayerCaches(rscByLayer);
            layeredRscHelper.cleanupResources(origResources);
        }
    }

    private Map<DeviceLayer, Set<RscLayerObject>> groupResourcesByLayer(Collection<Resource> allResources)
    {
        Map<DeviceLayer, Set<RscLayerObject>> ret = new HashMap<>();
        try
        {
            for (Resource rsc : allResources)
            {
                for (Volume vlm : rsc.streamVolumes().collect(Collectors.toList()))
                {
                    DeviceLayerKind rootKind = vlm.getLayerStack(wrkCtx).get(0);
                    RscLayerObject rootRscData = rsc.getLayerData(wrkCtx, rootKind);

                    LinkedList<RscLayerObject> toProcess = new LinkedList<>();
                    toProcess.add(rootRscData);
                    while (!toProcess.isEmpty())
                    {
                        RscLayerObject rscData = toProcess.poll();

                        toProcess.addAll(rscData.getChildren());

                        DeviceLayer devLayer = layerFactory.getDeviceLayer(rscData.getLayerKind());
                        ret.computeIfAbsent(devLayer, ignored -> new HashSet<>()).add(rscData);
                    }
                }
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ImplementationError(accDeniedExc);
        }
        return ret;
    }

    private Map<ResourceName, Set<Snapshot>> groupSnapshotsByResourceName(Collection<Snapshot> snapshots)
    {
        return snapshots.stream().collect(
            Collectors.groupingBy(
                Snapshot::getResourceName,
                Collectors.mapping(
                    Function.identity(),
                    Collectors.toSet()
                )
            )
        );
    }

    private void calculateGrossSizes(Collection<Resource> rootResources) throws ImplementationError
    {
        for (Resource rsc : rootResources)
        {
            try
            {
                updateGrossSizeForChildren(rsc);
            }
            catch (AccessDeniedException | SQLException exc)
            {
                throw new ImplementationError(exc);
            }
        }
    }

    private boolean prepareLayers(
        Map<DeviceLayer, Set<RscLayerObject>> rscByLayer,
        Map<ResourceName, Set<Snapshot>> snapshotsByRscName
    )
    {
        boolean prepareSuccess = true;
        for (Entry<DeviceLayer, Set<RscLayerObject>> entry : rscByLayer.entrySet())
        {
            Set<Snapshot> affectedSnapshots = new TreeSet<>();
            Set<RscLayerObject> rscDataList = entry.getValue();
            for (RscLayerObject rscData : rscDataList)
            {
                // FIXME: RAID: add rscNameSuffix
                Set<Snapshot> list = snapshotsByRscName.get(rscData.getResourceName());
                if (list != null)
                {
                    affectedSnapshots.addAll(list);
                }
            }
            DeviceLayer layer = entry.getKey();
            if (!prepare(layer, rscDataList, affectedSnapshots))
            {
                prepareSuccess = false;
                break;
            }
        }
        return prepareSuccess;
    }

    private void processResourcesAndTheirSnapshots(
        Collection<Snapshot> snapshots,
        Collection<Resource> rootResources,
        Map<ResourceName, Set<Snapshot>> snapshotsByRscName,
        List<Snapshot> unprocessedSnapshots,
        List<Resource> rscListNotifyApplied,
        List<Resource> rscListNotifyDelete,
        List<Snapshot> snapListNotifyDelete
    )
        throws ImplementationError
    {
        for (Resource rsc : rootResources)
        {
            ResourceName rscName = rsc.getDefinition().getName();

            Set<Snapshot> snapshotList = snapshotsByRscName.getOrDefault(rscName, Collections.emptySet());
            unprocessedSnapshots.removeAll(snapshotList);

            ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
            try
            {
                DeviceLayerKind rootKind = null;
                {
                    Optional<Volume> firstVlm = rsc.streamVolumes().findFirst();
                    if (firstVlm.isPresent())
                    {
                        rootKind = firstVlm.get().getLayerStack(wrkCtx).get(0);
                    }
                }

                if (rootKind != null)
                {
                    process(
                        rsc.getLayerData(wrkCtx, rootKind),
                        snapshotList,
                        apiCallRc
                    );

                    /*
                     * old device manager reported changes of free space after every
                     * resource operation. As this could require to query the same
                     * VG or zpool multiple times within the same device manager run,
                     * we only query the free space after the whole run.
                     * This also means that we only send the resourceApplied messages
                     * at the very end
                     */
                    if (rsc.getStateFlags().isSet(wrkCtx, RscFlags.DELETE))
                    {
                        rscListNotifyDelete.add(rsc);
                        notificationListener.get().notifyResourceDeleted(rsc);
                        // rsc.delete is done by the deviceManager
                    }
                    else
                    {
                        rscListNotifyApplied.add(rsc);
                        notificationListener.get().notifyResourceApplied(rsc);
                    }

                    for (Snapshot snapshot : snapshots)
                    {
                        if (snapshot.getFlags().isSet(wrkCtx, SnapshotFlags.DELETE))
                        {
                            snapListNotifyDelete.add(snapshot);
                            notificationListener.get().notifySnapshotDeleted(snapshot);
                            // snapshot.delete is done by the deviceManager
                        }
                    }
                }
            }
            catch (AccessDeniedException | SQLException exc)
            {
                throw new ImplementationError(exc);
            }
            catch (Exception | ImplementationError exc)
            {
                String errorId = errorReporter.reportError(
                    exc,
                    null,
                    null,
                    "An error occurred while processing resource '" + rsc + "'"
                );

                long rc;
                String errMsg;
                String cause;
                String correction;
                String details;
                if (exc instanceof StorageException ||
                    exc instanceof ResourceException ||
                    exc instanceof VolumeException
                )
                {
                    LinStorException linExc = (LinStorException) exc;
                    // TODO add returnCode and message to the classes StorageException, ResourceException and
                    // VolumeException and include them here

                    rc = ApiConsts.FAIL_UNKNOWN_ERROR;
                    errMsg = exc.getMessage();

                    cause = linExc.getCauseText();
                    correction = linExc.getCorrectionText();
                    details = linExc.getDetailsText();
                }
                else
                {
                    rc = ApiConsts.FAIL_UNKNOWN_ERROR;
                    errMsg = exc.getMessage();

                    cause = null;
                    correction = null;
                    details = null;
                }

                apiCallRc = ApiCallRcImpl.singletonApiCallRc(ApiCallRcImpl
                    .entryBuilder(rc, errMsg)
                    .setCause(cause)
                    .setCorrection(correction)
                    .setDetails(details)
                    .addErrorId(errorId)
                    .build()
                );
            }
            notificationListener.get().notifyResourceDispatchResponse(rscName, apiCallRc);
        }
    }

    private void processUnprocessedSnapshots(List<Snapshot> unprocessedSnapshots) throws ImplementationError
    {
        Map<ResourceName, List<Snapshot>> snapshotsByResourceName = unprocessedSnapshots.stream()
            .collect(Collectors.groupingBy(Snapshot::getResourceName));

        /*
         *  We cannot use the .process(Resource, List<Snapshot>, ApiCallRc) method as we do not have a
         *  resource. The resource is used for determining which DeviceLayer to use, thus would result in
         *  a NPE.
         *  However, actually we know that there are no resources "based" on these snapshots (else the
         *  DeviceManager would have found them and called the previous case, such that those snapshots
         *  would have been processed already).
         *  That means, we can skip all layers and go directory to the StorageLayer, which, fortunately,
         *  does not need a resource for processing snapshots.
         */
        for (Entry<ResourceName, List<Snapshot>> entry : snapshotsByResourceName.entrySet())
        {
            ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
            try
            {
                storageLayer.process(
                    null, // no resource, no rscLayerData
                    entry.getValue(), // list of snapshots
                    apiCallRc
                );
            }
            catch (AccessDeniedException | SQLException exc)
            {
                throw new ImplementationError(exc);
            }
            catch (StorageException | ResourceException | VolumeException exc)
            {
                // TODO different handling for different exceptions?
                String errorId = errorReporter.reportError(
                    exc,
                    null,
                    null,
                    "An error occurred while processing resource '" + entry.getKey() + "'"
                );

                apiCallRc = ApiCallRcImpl.singletonApiCallRc(ApiCallRcImpl
                    .entryBuilder(
                        // TODO maybe include a ret-code into the exception
                        ApiConsts.FAIL_UNKNOWN_ERROR,
                        exc.getMessage()
                    )
                    .setCause(exc.getCauseText())
                    .setCorrection(exc.getCorrectionText())
                    .setDetails(exc.getDetailsText())
                    .addErrorId(errorId)
                    .build()
                );
            }
            notificationListener.get().notifyResourceDispatchResponse(entry.getKey(), apiCallRc);
        }
    }

    private void notifyResourcesApplied(List<Resource> rscListNotifyApplied) throws ImplementationError
    {
        Peer ctrlPeer = controllerPeerConnector.getControllerPeer();
        if (ctrlPeer != null)
        {
            try
            {
                Map<StorPool, Either<SpaceInfo, ApiRcException>> spaceInfoQueryMap =
                    storageLayer.getFreeSpaceOfAccessedStoagePools();

                Map<StorPool, SpaceInfo> spaceInfoMap = new TreeMap<>();

                spaceInfoQueryMap.forEach((storPool, either) -> either.consume(
                    spaceInfo -> spaceInfoMap.put(storPool, spaceInfo),
                    apiRcException -> errorReporter.reportError(apiRcException.getCause())
                ));

                // TODO: rework API answer
                /*
                 * Instead of sending single change, request and applied / deleted messages per
                 * resource, the controller and satellite should use one message containing
                 * multiple resources.
                 * The final message regarding applied and/or deleted resource can so also contain
                 * the new free spaces of the affected storage pools
                 */

                for (Resource rsc : rscListNotifyApplied)
                {
                    ctrlPeer.sendMessage(
                        interComSerializer
                            .onewayBuilder(InternalApiConsts.API_NOTIFY_RSC_APPLIED)
                            .notifyResourceApplied(rsc, spaceInfoMap)
                            .build()
                    );
                }
            }
            catch (AccessDeniedException accDeniedExc)
            {
                throw new ImplementationError(accDeniedExc);
            }
        }
    }

    private void clearLayerCaches(Map<DeviceLayer, Set<RscLayerObject>> rscByLayer)
    {
        for (Entry<DeviceLayer, Set<RscLayerObject>> entry : rscByLayer.entrySet())
        {
            DeviceLayer layer = entry.getKey();
            try
            {
                layer.clearCache();
            }
            catch (StorageException exc)
            {
                errorReporter.reportError(exc);
                ApiCallRcImpl apiCallRc = ApiCallRcImpl.singletonApiCallRc(
                    ApiCallRcImpl.entryBuilder(
                        ApiConsts.FAIL_UNKNOWN_ERROR,
                        "An error occured while cleaning up layer '" + layer.getName() + "'"
                    )
                    .setCause(exc.getCauseText())
                    .setCorrection(exc.getCorrectionText())
                    .setDetails(exc.getDetailsText())
                    .build()
                );
                for (RscLayerObject rsc : entry.getValue())
                {
                    notificationListener.get().notifyResourceDispatchResponse(
                        rsc.getResourceName(),
                        apiCallRc
                    );
                }
            }
        }
    }

    private boolean prepare(DeviceLayer layer, Set<RscLayerObject> rscDataList, Set<Snapshot> affectedSnapshots)
    {
        boolean success;
        try
        {
            errorReporter.logTrace(
                "Layer '%s' preparing %d resources",
                layer.getName(),
                rscDataList.size()
            );
            layer.prepare(rscDataList, affectedSnapshots);
            errorReporter.logTrace(
                "Layer '%s' finished preparing %d resources",
                layer.getName(),
                rscDataList.size()
            );
            success = true;
        }
        catch (StorageException exc)
        {
            success = false;
            errorReporter.reportError(exc);

            ApiCallRcImpl apiCallRc = ApiCallRcImpl.singletonApiCallRc(
                ApiCallRcImpl.entryBuilder(
                    // TODO maybe include a ret-code into the StorageException
                    ApiConsts.FAIL_UNKNOWN_ERROR,
                    "Preparing resources for layer " + layer.getName() + " failed"
                )
                .setCause(exc.getCauseText())
                .setCorrection(exc.getCorrectionText())
                .setDetails(exc.getDetailsText())
                .build()
            );
            for (RscLayerObject failedResourceData : rscDataList)
            {
                notificationListener.get().notifyResourceDispatchResponse(
                    failedResourceData.getResourceName(),
                    apiCallRc
                );
            }
        }
        catch (AccessDeniedException | SQLException exc)
        {
            throw new ImplementationError(exc);
        }
        return success;
    }


    @Override
    public void process(
        RscLayerObject rscLayerData,
        Collection<Snapshot> snapshots,
        ApiCallRcImpl apiCallRc
    )
        throws StorageException, ResourceException, VolumeException, AccessDeniedException, SQLException
    {
        DeviceLayer nextLayer = layerFactory.getDeviceLayer(rscLayerData.getLayerKind());

        errorReporter.logTrace(
            "Layer '%s' processing resource '%s'",
            nextLayer.getName(),
            rscLayerData.getSuffixedResourceName()
        );

        nextLayer.process(rscLayerData, snapshots, apiCallRc);

        errorReporter.logTrace(
            "Layer '%s' finished processing resource '%s'",
            nextLayer.getName(),
            rscLayerData.getSuffixedResourceName()
        );
    }

    public void updateGrossSizeForChildren(Resource rsc) throws AccessDeniedException, SQLException
    {
        for (Volume vlm : rsc.streamVolumes().collect(Collectors.toList()))
        {
            { // set initial size which will be changed by the actual layers shortly
                long size = vlm.getVolumeDefinition().getVolumeSize(wrkCtx);
                vlm.setAllocatedSize(wrkCtx, size);
                vlm.setUsableSize(wrkCtx, size);
            }

            VolumeNumber vlmNr = vlm.getVolumeDefinition().getVolumeNumber();

            RscLayerObject rscData = null;
            VlmProviderObject vlmData = null;
            for (DeviceLayerKind kind : vlm.getLayerStack(wrkCtx))
            {
                if (rscData == null)
                {
                    rscData = rsc.getLayerData(wrkCtx, kind);
                }
                else
                {
                    rscData = rscData.getSingleChild();
                }
                vlmData = rscData.getVlmProviderObject(vlmNr);
                layerFactory.getDeviceLayer(kind).updateGrossSize(vlmData);
            }
        }
    }

    // TODO: create delete volume / resource mehtods that (for now) only perform the actual .delete()
    // command. This method should be a central point for future logging or other extensions / purposes

    /**
     * This method splits one {@link Resource} into device-layer-specific resources.
     * In future versions of LINSTOR this method should get obsolete as the API layer should
     * already receive the correct resources.
     * @param snapshotsRef
     * @throws AccessDeniedException
     */
    @RemoveAfterDevMgrRework
    private Collection<Resource> convertResources(
        Collection<Resource> resourcesToProcess,
        Collection<Snapshot> snapshots
    )
    {
        // convert resourceNames to resources
        return layeredRscHelper.extractLayers(resourcesToProcess);
    }

    public void fullSyncApplied(Node localNode)
    {
        fullSyncApplied.set(true);
        try
        {
            Props localNodeProps = localNode.getProps(wrkCtx);
            localNodePropsChanged(localNodeProps);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    // FIXME: this method also needs to be called when the localnode's properties change, not just
    // (as currently) when a fullSync was applied
    public void localNodePropsChanged(Props localNodeProps)
    {
        layerFactory.streamDeviceHandlers().forEach(
            rscLayer ->
                rscLayer.setLocalNodeProps(localNodeProps));
    }

    public void checkConfig(StorPool storPool) throws StorageException, AccessDeniedException
    {
        storageLayer.checkStorPool(storPool);
    }
}
