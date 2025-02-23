package com.linbit.linstor.api;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.api.ApiConsts.ConnectionStatus;
import com.linbit.linstor.api.interfaces.AutoSelectFilterApi;
import com.linbit.linstor.api.utils.AbsApiCallTester;
import com.linbit.linstor.core.ApiTestBase;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.core.apicallhandler.controller.CtrlRscAutoPlaceApiCallHandler;
import com.linbit.linstor.core.apicallhandler.controller.CtrlRscCrtApiHelper;
import com.linbit.linstor.core.apicallhandler.controller.FreeCapacityFetcher;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.SharedStorPoolName;
import com.linbit.linstor.core.identifier.StorPoolName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.FreeSpaceMgr;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.ResourceConnection;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.ResourceGroup;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.core.objects.StorPoolDefinition;
import com.linbit.linstor.core.objects.Volume;
import com.linbit.linstor.layer.LayerPayload;
import com.linbit.linstor.layer.LayerPayload.DrbdRscDfnPayload;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.GenericDbBase;
import com.linbit.linstor.storage.interfaces.layers.drbd.DrbdRscDfnObject.TransportType;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.storage.kinds.ExtTools;
import com.linbit.linstor.storage.kinds.ExtToolsInfo;
import com.linbit.linstor.storage.kinds.ExtToolsInfo.Version;
import com.linbit.linstor.utils.externaltools.ExtToolsManager;

import static com.linbit.linstor.storage.kinds.DeviceLayerKind.DRBD;
import static com.linbit.linstor.storage.kinds.DeviceLayerKind.LUKS;
import static com.linbit.linstor.storage.kinds.DeviceLayerKind.STORAGE;
import static com.linbit.linstor.storage.kinds.DeviceProviderKind.LVM;
import static com.linbit.linstor.storage.kinds.DeviceProviderKind.LVM_THIN;
import static com.linbit.linstor.storage.kinds.DeviceProviderKind.ZFS;
import static com.linbit.linstor.storage.kinds.DeviceProviderKind.ZFS_THIN;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import com.google.inject.testing.fieldbinder.Bind;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@SuppressWarnings({"checkstyle:magicnumber", "checkstyle:descendenttokencheck"})
public class RscAutoPlaceApiTest extends ApiTestBase
{
    private static final long KB = 1;
    private static final long MB = 1_000 * KB;
    private static final long GB = 1_000 * MB;
    private static final long TB = 1_000 * GB;

    private static final String TEST_RSC_NAME = "TestRsc";
    private static final int TEST_TCP_PORT_NR = 8000;

    private static final int MINOR_NR_MIN = 1000;
    private static final AtomicInteger MINOR_GEN = new AtomicInteger(MINOR_NR_MIN);

    @Inject private CtrlRscAutoPlaceApiCallHandler rscAutoPlaceApiCallHandler;
    @Inject private CtrlRscCrtApiHelper ctrlRscCrtApiHelper;

    private static final StorPoolName DFLT_DISKLESS_STOR_POOL_NAME;

    static
    {
        try
        {
            DFLT_DISKLESS_STOR_POOL_NAME = new StorPoolName(LinStor.DISKLESS_STOR_POOL_NAME);
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    @Bind @Mock
    protected FreeCapacityFetcher freeCapacityFetcher;
    private ResourceGroup dfltRscGrp;

    @Before
    @Override
    public void setUp() throws Exception
    {
        seedDefaultPeerRule.setDefaultPeerAccessContext(BOB_ACC_CTX);
        super.setUp();
        dfltRscGrp = createDefaultResourceGroup(BOB_ACC_CTX);
        createRscDfn(TEST_RSC_NAME, TEST_TCP_PORT_NR);
        MINOR_GEN.set(MINOR_NR_MIN);

        Mockito.when(mockPeer.getAccessContext()).thenReturn(BOB_ACC_CTX);

        Mockito.when(freeCapacityFetcher.fetchThinFreeCapacities(any())).thenReturn(Mono.just(Collections.emptyMap()));

        Optional<Props> optAutoplacerNamespace = ctrlConf.getNamespace(ApiConsts.NAMESPC_AUTOPLACER_WEIGHTS);
        if (optAutoplacerNamespace.isPresent())
        {
            optAutoplacerNamespace.get().clear();
        }

        AtomicInteger atomicTcpPorts = new AtomicInteger(TEST_TCP_PORT_NR + 1);
        Mockito.when(tcpPortPoolMock.autoAllocate()).thenReturn(atomicTcpPorts.incrementAndGet());

        commitAndCleanUp(true);
    }

    @After
    @Override
    public void tearDown() throws Exception
    {
        commitAndCleanUp(false);
    }

    @Test
    public void basicTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED,
                ApiConsts.CREATED,
                ApiConsts.CREATED // rsc autoplace
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * TB)
        );
        expectDeployed(
            "slow1",
            TEST_RSC_NAME,
            "stlt1", "stlt2"
        );
    }

    @Test
    public void chooseLargerSatelliteTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                1,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            // Name and order the options so that the expected choice is in the middle in terms of creation sequence
            // and lexicographic order in order to minimize the chances of choosing correctly by accident
            .stltBuilder("stlt1")
                .addStorPool("pool", 10 * MB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("pool", 30 * MB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("pool", 20 * MB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * MB)
        );
        expectDeployed(
            "pool",
            TEST_RSC_NAME,
            "stlt2"
        );
    }

    @Test
    public void chooseLargerPoolTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                1,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .stltBuilder("stlt")
                // Name and order the options so that the expected choice is in the middle in terms of creation sequence
                // and lexicographic order in order to minimize the chances of choosing correctly by accident
                .addStorPool("pool1", 10 * MB)
                .addStorPool("pool2", 30 * MB)
                .addStorPool("pool3", 20 * MB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * MB)
        );
        expectDeployed(
            "pool2",
            TEST_RSC_NAME,
            "stlt"
        );
    }

    @Test
    public void preferredStorPoolNotEnoughSpaceTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                false,
                ApiConsts.FAIL_NOT_ENOUGH_NODES
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * TB)
            .addStorPool("fast1")
        );
        expectNotDeployed(TEST_RSC_NAME);
    }

    @Test
    public void preferredStorPoolSuccessTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 50 * GB)
            .addStorPool("fast1")
        );

        expectDeployed(
            "fast1",
            TEST_RSC_NAME,
            "stlt1", "stlt2"
        );
    }

    @Test
    public void doNotPlaceWithRscTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("slow1", 80*GB)
                .build()
            .stltBuilder("stlt4")
                .addStorPool("slow1", 80*GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 50 * GB)

            .doNotPlaceWith("avoid1")

            .addRscDfn("avoid1", TEST_TCP_PORT_NR + 1)
            .addVlmDfn("avoid1", 0, 2 * TB)
            .addRsc("avoid1", "slow1", "stlt1", "stlt2")
        );

        // do not place with is a node-level check, not a storpool-level
        expectDeployed(
            "slow1",
            TEST_RSC_NAME,
            "stlt3", "stlt4"
        );
    }

    @Test
    public void notEnoughNodesTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                3,
                false,
                ApiConsts.FAIL_NOT_ENOUGH_NODES
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * TB)
        );
        expectNotDeployed(TEST_RSC_NAME);
    }

    @Test
    public void doNotPlaceWithRscAndStorPoolTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                false,
                ApiConsts.FAIL_NOT_ENOUGH_NODES
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * TB)

            .doNotPlaceWith("avoid1")
                .addStorPool("slow1")

            .addRscDfn("avoid1", TEST_TCP_PORT_NR + 1)
            .addVlmDfn("avoid1", 0, 2 * TB)
            .addRsc("avoid1", "slow1", "stlt1", "stlt2")
        );
        expectNotDeployed(TEST_RSC_NAME);
    }

    @Test
    public void doNotPlaceWithRscRegexTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("slow2", 20 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("slow2", 20 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("fast1", 80 * GB)
                .build()
            .stltBuilder("stlt4")
                .addStorPool("fast1", 80 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 50 * GB)

            .setDoNotPlaceWithRegex("avoid.*")

            .addRscDfn("avoid1", TEST_TCP_PORT_NR + 1)
            .addVlmDfn("avoid1", 0, 2 * TB)
                .addRsc("avoid1", "slow1", "stlt1", "stlt2")
            .addRscDfn("avoid2", TEST_TCP_PORT_NR + 2)
            .addVlmDfn("avoid2", 0, 2 * TB)
                .addRsc("avoid2", "slow2", "stlt1", "stlt2")
        );

        expectDeployed(
            "fast1",
            TEST_RSC_NAME,
            "stlt3", "stlt4"
        );
    }

    @Test
    public void doNotPlaceWithRscSimpleRegexPrefixTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .stltBuilder("stlt1")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("slow2", 20 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("slow1", 10 * TB)
                .addStorPool("slow2", 20 * TB)
                .addStorPool("fast1", 100 * GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("fast1", 80 * GB)
                .build()
            .stltBuilder("stlt4")
                .addStorPool("fast1", 80 * GB)
                .build()
            .addVlmDfn(TEST_RSC_NAME, 0, 50 * GB)

            .setDoNotPlaceWithRegex("avoid") // no trailing ".*"

            .addRscDfn("avoid1", TEST_TCP_PORT_NR + 1)
            .addVlmDfn("avoid1", 0, 2 * TB)
                .addRsc("avoid1", "slow1", "stlt1", "stlt2")
            .addRscDfn("avoid2", TEST_TCP_PORT_NR + 2)
            .addVlmDfn("avoid2", 0, 2 * TB)
                .addRsc("avoid2", "slow2", "stlt1", "stlt2")
        );

        // do not place with is a node-level check, not a storpool-level
        expectDeployed(
            "fast1",
            TEST_RSC_NAME,
            "stlt3", "stlt4"
        );
    }

    @Test
    public void replicasCombinedTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt.A1.B1.1")
                .setNodeProp("Aux/A", "1")
                .setNodeProp("Aux/B", "1")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A1.B1.2")
                .setNodeProp("Aux/A", "1")
                .setNodeProp("Aux/B", "1")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A1.B2.1")
                .setNodeProp("Aux/A", "1")
                .setNodeProp("Aux/B", "2")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A1.B2.2")
                .setNodeProp("Aux/A", "1")
                .setNodeProp("Aux/B", "2")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A1.B3.1")
                .setNodeProp("Aux/A", "1")
                .setNodeProp("Aux/B", "3")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A1.B3.2")
                .setNodeProp("Aux/A", "1")
                .setNodeProp("Aux/B", "3")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A2.B1.1")
                .setNodeProp("Aux/A", "2")
                .setNodeProp("Aux/B", "1")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A2.B1.2")
                .setNodeProp("Aux/A", "2")
                .setNodeProp("Aux/B", "1")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A2.B2.1")
                .setNodeProp("Aux/A", "2")
                .setNodeProp("Aux/B", "2")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A2.B2.2")
                .setNodeProp("Aux/A", "2")
                .setNodeProp("Aux/B", "2")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A2.B3.1")
                .setNodeProp("Aux/A", "2")
                .setNodeProp("Aux/B", "3")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("stlt.A2.B3.2")
                .setNodeProp("Aux/A", "2")
                .setNodeProp("Aux/B", "3")
                .addStorPool("stor", 10 * GB)
                .build()

            .addReplicasOnSameNodeProp("Aux/A")
            .addReplicasOnDifferentNodeProp("Aux/B")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode()) // we should have now only 2 nodes
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        Props firstNodeProps = deployedNodes.get(0).getProps(GenericDbBase.SYS_CTX);
        Props secondNodeProps = deployedNodes.get(1).getProps(GenericDbBase.SYS_CTX);

        assertEquals(firstNodeProps.getProp("Aux/A"), secondNodeProps.getProp("Aux/A"));
        assertNotEquals(firstNodeProps.getProp("Aux/B"), secondNodeProps.getProp("Aux/B"));
    }

    @Test
    public void replicasOnCombinedWithValueTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                1,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("node0.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node1.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node2.val2")
                .setNodeProp("Aux/A", "2")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node3.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node4.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 10 * GB)
                .build()

            .addReplicasOnSameNodeProp("Aux/A=1")
            .addReplicasOnDifferentNodeProp("Aux/A=0")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode) // we should have now only 2 nodes
            .collect(Collectors.toList());
        assertEquals(1, deployedNodes.size());

        String nodePropVal = deployedNodes.get(0).getProps(GenericDbBase.SYS_CTX).getProp("Aux/A");

        assertEquals("1", nodePropVal);
        assertNotEquals("0", nodePropVal);
    }

    @Test
    public void replicasOnDifferentWithValueGithub79Test() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
                .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
                .stltBuilder("node1")
                    .setNodeProp("Aux/key", "val1")
                    .addStorPool("stor", 10 * GB)
                    .build()
                .stltBuilder("node2")
                    .setNodeProp("Aux/key", "val2")
                    .addStorPool("stor", 10 * GB)
                    .build()
                .stltBuilder("node3")
                    .setNodeProp("Aux/key", "val3")
                    .addStorPool("stor", 10 * GB)
                    .build()
                .stltBuilder("node4")
                    .setNodeProp("Aux/key", "val1") // same as "node1"
                    .addStorPool("stor", 10 * GB)
                    .build()
                .stltBuilder("node5")
                    .setNodeProp("Aux/key", "val2") // same as "node2"
                    .addStorPool("stor", 9 * GB) // make node2 a bit better
                    .build()

                .addReplicasOnDifferentNodeProp("Aux/key=val1")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode) // we should have now only 2 nodes
            .sorted((n1, n2) -> n1.getName().compareTo(n2.getName()))
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        String nodePropVal = deployedNodes.get(0).getProps(GenericDbBase.SYS_CTX).getProp("Aux/key");

        assertNotEquals("val1", nodePropVal);

        assertEquals("node2", deployedNodes.get(0).getName().displayValue);
        assertEquals("node3", deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void replicasOnDifferentWithOutValueTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("node0.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node1.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node2.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node3.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 100 * GB)
                .build()
            .stltBuilder("node4.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 100 * GB)
                .build()

            .addReplicasOnDifferentNodeProp("Aux/A")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode) // we should have now only 2 nodes
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        String firstNodePropVal = deployedNodes.get(0).getProps(GenericDbBase.SYS_CTX).getProp("Aux/A");
        String secondNodePropVal = deployedNodes.get(1).getProps(GenericDbBase.SYS_CTX).getProp("Aux/A");

        assertNotEquals(secondNodePropVal, firstNodePropVal);
    }

    @Test
    public void replicasOnDifferentWithValueTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
                .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
                .stltBuilder("node0.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
                .stltBuilder("node1.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 10 * GB)
                .build()
                .stltBuilder("node2.val2")
                .setNodeProp("Aux/A", "2")
                .addStorPool("stor", 10 * GB)
                .build()
                .stltBuilder("node3.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
                .stltBuilder("node4.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 10 * GB)
                .build()

                .addReplicasOnDifferentNodeProp("Aux/A=0")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode) // we should have now only 2 nodes
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        String firstNodePropVal = deployedNodes.get(0).getProps(GenericDbBase.SYS_CTX).getProp("Aux/A");
        String secondNodePropVal = deployedNodes.get(1).getProps(GenericDbBase.SYS_CTX).getProp("Aux/A");

        assertNotEquals("0", firstNodePropVal);
        assertNotEquals("0", secondNodePropVal);
    }

    @Test
    public void replicasOnSameTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                5,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("node0.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node1.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node2.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node3.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()
            .stltBuilder("node4.val0")
                .setNodeProp("Aux/A", "0")
                .addStorPool("stor", 10 * GB)
                .build()

            .stltBuilder("node5.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 20 * GB)
                .build()
            .stltBuilder("node6.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 20 * GB)
                .build()
            .stltBuilder("node7.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 20 * GB)
                .build()
            .stltBuilder("node8.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 20 * GB)
                .build()
            .stltBuilder("node9.val1")
                .setNodeProp("Aux/A", "1")
                .addStorPool("stor", 20 * GB)
                .build()

            .addReplicasOnSameNodeProp("Aux/A")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode) // we should have now only 2 nodes
            .collect(Collectors.toList());
        assertEquals(5, deployedNodes.size());

        for (int idx = 0; idx < 5; idx++)
        {
            assertEquals("1", deployedNodes.get(idx).getProps(GenericDbBase.SYS_CTX).getProp("Aux/A"));
        }
    }

    @Test
    public void replicasCombinedGithub89Test() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 100 * GB)
            .stltBuilder("m13c12")
                .addStorPool("thindata", null, 179 * GB, 900 * GB, LVM_THIN)
                .setNodeProp("Aux/moonshot", "13")
                .setNodeProp("Aux/opennebula-1", "true")
                .build()
            .stltBuilder("m14c21")
                .addStorPool("thindata", null, 203 * GB, 900 * GB, LVM_THIN)
                .setNodeProp("Aux/moonshot", "14")
                .setNodeProp("Aux/opennebula-1", "true")
                .build()
            .stltBuilder("m10c12")
                .addStorPool("thindata", null, 900 * GB, 900 * GB, LVM_THIN)
                .setNodeProp("Aux/moonshot", "10")
                .setNodeProp("Aux/opennebula-1", "true")
                .build()
            .stltBuilder("m15c12")
                .addStorPool("thindata", null, 900 * GB, 900 * GB, LVM_THIN)
                .setNodeProp("Aux/moonshot", "15")
                .setNodeProp("Aux/opennebula-1", "true")
                .build()

            .addReplicasOnSameNodeProp("Aux/opennebula-1")
            .addReplicasOnDifferentNodeProp("Aux/moonshot")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode)
            .collect(Collectors.toList());

        assertEquals(2, deployedNodes.size());
        assertEquals("m10c12", deployedNodes.get(0).getName().displayValue);
        assertEquals("m15c12", deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void avoidNodeWithoutPropWhenUsingReplOnSameTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100*GB)
                // prefered from free space, but missing "rack" property
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 90*GB)
                // second prefered from free space, but has no second node with same "rack" property
                .setNodeProp("Aux/rack", "1")
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 10*GB)
                .setNodeProp("Aux/rack", "2")
                .build()
            .stltBuilder("stlt4")
                .addStorPool("sp1", 10*GB)
                .setNodeProp("Aux/rack", "2")
                .build()

            .addReplicasOnSameNodeProp("Aux/rack")
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(Resource::getNode)
            .collect(Collectors.toList());

        assertEquals(2, deployedNodes.size());
        assertEquals("stlt3", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt4", deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void disklessRemainingTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt2").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt3").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt4").addStorPool("stor", 10 * GB).build()
            .disklessOnRemaining(true)
        );

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode()) // we should have now only 2 diskfull and 2 diskless nodes
            .collect(Collectors.toList());
        assertEquals(4, deployedNodes.size());

        long disklessNodes = deployedNodes.stream().filter(
            node ->
            {
                assertEquals(1, node.getResourceCount()); // just to be sure
                try
                {
                    return node.getResource(GenericDbBase.SYS_CTX, new ResourceName(TEST_RSC_NAME))
                        .getStateFlags()
                        .isSet(GenericDbBase.SYS_CTX, Resource.Flags.DRBD_DISKLESS);
                }
                catch (AccessDeniedException | InvalidNameException exc)
                {
                    throw new RuntimeException(exc);
                }
            }
        ).count();
        long diskfullNodes = deployedNodes.size() - disklessNodes;

        assertEquals(2, disklessNodes);
        assertEquals(2, diskfullNodes);
    }

    @Test
    public void idempotencyTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt2").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt3").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt4").addStorPool("stor", 10 * GB).build()
            .disklessOnRemaining(false)
        );

        // we should now have some resources deployed. We do not really care where they are
        // but still make sure that they are 2.

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        // rerun the same apiCall, but this time we should receive a different RC
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                false,
                ApiConsts.MASK_CRT | ApiConsts.MASK_RSC | ApiConsts.WARN_RSC_ALREADY_DEPLOYED // rsc autoplace
            )
            // no need for addVlmDfn or stltBuilderCalls. We are in the same instance, the controller
            // should still know about the previously configured objects
        );

        // recheck
        deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());
    }

    @Test
    public void extendAutoPlacedRscTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt2").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt3").addStorPool("stor", 10 * GB).build()
            .stltBuilder("stlt4").addStorPool("stor", 10 * GB).build()
            .disklessOnRemaining(false)
        );

        // we should now have some resources deployed. We do not really care where they are
        // but still make sure that they are 2.

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        // rerun the same apiCall, but this time with +1 replicas
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                3,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            // no need for addVlmDfn or stltBuilderCalls. We are in the same instance, the controller
            // should still know about the previously configured objects
            .disklessOnRemaining(true)
        );

        // recheck
        deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(4, deployedNodes.size());

        long disklessNodes = deployedNodes.stream().filter(
            node ->
            {
                assertEquals(1, node.getResourceCount()); // just to be sure
                try
                {
                    return node.getResource(GenericDbBase.SYS_CTX, new ResourceName(TEST_RSC_NAME))
                        .getStateFlags()
                        .isSet(GenericDbBase.SYS_CTX, Resource.Flags.DRBD_DISKLESS);
                }
                catch (AccessDeniedException | InvalidNameException exc)
                {
                    throw new RuntimeException(exc);
                }
            }
        ).count();
        long diskfullNodes = deployedNodes.size() - disklessNodes;

        assertEquals(1, disklessNodes);
        assertEquals(3, diskfullNodes);
    }

    @Test
    public void extendAutoPlacedRscOnDifferentStorPoolsTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1").addStorPool("stor", 100 * GB).build()
            .stltBuilder("stlt2").addStorPool("stor", 100 * GB).build()
            .stltBuilder("stlt3").addStorPool("stor", 10 * GB).addStorPool("stor2", 10 * GB).build()
            .stltBuilder("stlt4").addStorPool("stor", 10 * GB).addStorPool("stor2", 10 * GB).build()
            .disklessOnRemaining(false)
        );

        // we should now have some resources deployed, namely on stlt1 and stlt2 (size of storpools)

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());

        assertThat(deployedNodes.stream().map(node -> node.getName().displayValue).collect(Collectors.toList()))
            .contains("stlt1", "stlt2");

        // rerun the same apiCall, but this time with +1 replicas
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                4,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            // no need for addVlmDfn or stltBuilderCalls. We are in the same instance, the controller
            // should still know about the previously configured objects
                .addStorPool(
                    "stor2"
                )
            .disklessOnRemaining(true)
        );

        // recheck
        deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(4, deployedNodes.size());

        ResourceName rscName = new ResourceName(TEST_RSC_NAME);
        Assert.assertEquals(
            "stor",
            nodesMap.get(new NodeName("stlt1"))
                .getResource(GenericDbBase.SYS_CTX, rscName)
                .getLayerData(SYS_CTX) // drbd layer
                .getSingleChild() // storage layer
                .getVlmProviderObject(new VolumeNumber(0))
                .getStorPool()
                .getName()
                .displayValue
        );
        Assert.assertEquals(
            "stor",
            nodesMap.get(new NodeName("stlt2"))
                .getResource(GenericDbBase.SYS_CTX, rscName)
                .getLayerData(SYS_CTX) // drbd layer
                .getSingleChild() // storage layer
                .getVlmProviderObject(new VolumeNumber(0))
                .getStorPool()
                .getName()
                .displayValue
        );
        Assert.assertEquals(
            "stor2",
            nodesMap.get(new NodeName("stlt3"))
                .getResource(GenericDbBase.SYS_CTX, rscName)
                .getLayerData(SYS_CTX) // drbd layer
                .getSingleChild() // storage layer
                .getVlmProviderObject(new VolumeNumber(0))
                .getStorPool()
                .getName()
                .displayValue
        );
        Assert.assertEquals(
            "stor2",
            nodesMap.get(new NodeName("stlt4"))
                .getResource(GenericDbBase.SYS_CTX, rscName)
                .getLayerData(SYS_CTX) // drbd layer
                .getSingleChild() // storage layer
                .getVlmProviderObject(new VolumeNumber(0))
                .getStorPool()
                .getName()
                .displayValue
        );
    }

    @Test
    public void layerStackTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1")
                .addStorPool("stor", 10 * GB)
                .setSupportedLayers(DRBD, LUKS, STORAGE)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("stor", 10 * GB)
                .setSupportedLayers(DRBD, LUKS, STORAGE)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("stor", 100 * GB)
                .setSupportedLayers(LUKS, STORAGE) // no DRBD :(
                .build()
            .setLayerStack(DRBD, STORAGE)
            .disklessOnRemaining(false)
        );

        // although stlt3 has the most storage, as it does not support DRBD, the other two
        // should be selected

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());
        assertEquals("stlt1", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt2", deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void providerTest() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1")
                .addStorPool("stor", 10 * GB, LVM)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("stor", 10 * GB, LVM)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("stor", 100 * GB, ZFS) // no LVM
                .build()
            .setProvider(LVM, LVM_THIN)
            .disklessOnRemaining(false)
        );

        // although stlt3 has the most storage, as it does not support LVM, the other two
        // should be selected

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());
        assertEquals("stlt1", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt2", deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void repsectNodePropertiesFixedByAlreadyDepoyedResource() throws Exception
    {
        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                2,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addVlmDfn(TEST_RSC_NAME, 0, 5 * GB)
            .stltBuilder("stlt1")
                .addStorPool("stor", 100 * GB, LVM)
                .setNodeProp("Aux/a", "a")
                .build()
            .stltBuilder("stlt2")
                .addStorPool("stor", 90 * GB, LVM)
                .setNodeProp("Aux/a", "a")
                .build()
            .stltBuilder("stlt3")
                .addStorPool("stor", 70 * GB, LVM)
                .setNodeProp("Aux/a", "a")
                .build()
            .stltBuilder("stlt4")
                .addStorPool("stor", 80 * GB, LVM)
                .setNodeProp("Aux/a", "b")
                .build()
            .disklessOnRemaining(false)
        );
        // will prioritize stlt1 and stlt2 based on free space

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(2, deployedNodes.size());
        assertEquals("stlt1", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt2", deployedNodes.get(1).getName().displayValue);

        evaluateTest(
            new RscAutoPlaceApiCall(
                TEST_RSC_NAME,
                3,
                true,
                ApiConsts.CREATED, // property set
                ApiConsts.CREATED // rsc autoplace
            )
            .addReplicasOnSameNodeProp("Aux/a")
        );

        // although stlt 4 has more free space, the replicasOnSame forces the selection to stlt3
        deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .collect(Collectors.toList());
        assertEquals(3, deployedNodes.size());
        deployedNodes.stream().map(node -> node.getName().displayValue).forEach(System.out::println);
        assertEquals("stlt1", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt2", deployedNodes.get(1).getName().displayValue);
        assertEquals("stlt3", deployedNodes.get(2).getName().displayValue);
    }

    @Test
    public void scalingTest() throws Exception
    {
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            2,
            true,
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED // rsc autoplace
        ).addVlmDfn(TEST_RSC_NAME, 0, 1 * GB);

        for (int nodeIdx = 0; nodeIdx < 512; nodeIdx++)
        {
            int a = (nodeIdx >> 8) % 2;
            int b = (nodeIdx >> 7) % 2;
            int c = (nodeIdx >> 6) % 2;
            int d = (nodeIdx >> 5) % 2;
            int e = (nodeIdx >> 4) % 2;
            int f = (nodeIdx >> 3) % 2;
            int g = (nodeIdx >> 2) % 2;
            int h = (nodeIdx >> 1) % 2;
            int i = nodeIdx % 2;

            call = call.stltBuilder(String.format("stlt.%03d", nodeIdx)) // fill with leading zeroes
                .addStorPool("sp" + nodeIdx + "_1", 20 * GB, LVM)
                .setNodeProp("Aux/a", ""+a)
                .setNodeProp("Aux/b", ""+b)
                .setNodeProp("Aux/c", ""+c)
                .setNodeProp("Aux/d", ""+d)
                .setNodeProp("Aux/e", ""+e)
                .setNodeProp("Aux/f", ""+f)
                .setNodeProp("Aux/g", ""+g)
                .setNodeProp("Aux/h", ""+h)
                .setNodeProp("Aux/i", ""+i)
                .build();
        }

        /*
         * we just created 512 nodes with one SP each. half of the nodes have Aux/a set to 0
         * the other half set to 1.
         * You can imagine these 512 nodes in a truth-table, where the properties
         * a,b,c,d,e,f,g,h and i are boolean variables in all possible combinations.
         */
        /*
         * now we have to configure the autoplacer to choose storage pools which we can
         * deterministically expect :)
         *
         * However, as this is a scaling test, forcing properties to a certain value does not
         * test scaling properly. We have 9 properties. If we set all but one property to a
         * certain value, the very first step (i.e. "Filter") of the new autoplacer will
         * actually remove all non-matching combination, making this test way too easy for
         * the autoplacer.
         *
         * For stressing the autoplacer we use 8 non-fixed replicas-on-same properties, allowing
         * the last property to be different of the two selected nodes
         */

        call = call
            // not adding Aux/a
            .addReplicasOnSameNodeProp("Aux/b")
            .addReplicasOnSameNodeProp("Aux/c")
            .addReplicasOnSameNodeProp("Aux/d")
            .addReplicasOnSameNodeProp("Aux/e")
            .addReplicasOnSameNodeProp("Aux/f")
            .addReplicasOnSameNodeProp("Aux/g")
            .addReplicasOnSameNodeProp("Aux/h")
            .addReplicasOnSameNodeProp("Aux/i");

        /*
         * we know that 8 of the 9 properties have to be the same, that means either 1 or 0.
         * we also know that a (the "most significant bit") will differ.
         * that means, the difference in the index of the two satellites will be 256
         */

        Level logLevel = errorReporter.getCurrentLogLevel();
        errorReporter.setLogLevel(SYS_CTX, Level.TRACE, Level.TRACE);
        long start = System.currentTimeMillis();
        evaluateTest(call);
        System.out.println((System.currentTimeMillis() - start));
        errorReporter.setLogLevel(SYS_CTX, logLevel, logLevel);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(2, deployedNodes.size());
        int lowerId = Integer.parseInt(deployedNodes.get(0).getName().displayValue.substring("stlt.".length()));
        assertEquals(String.format("stlt.%03d", lowerId + 256), deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void minRscCountStrategyTest() throws Exception
    {
        enterScope();
        ctrlConf.setProp(ApiConsts.NAMESPC_AUTOPLACER_WEIGHTS + "/FreeSpace", "0");
        ctrlConf.setProp(ApiConsts.NAMESPC_AUTOPLACER_WEIGHTS + "/MinRscCount", "1");
        commitAndCleanUp(true);

        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            1,
            true,
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED // rsc autoplace
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100*GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 90*GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 50*GB)
                .build()
            .addRscDfn("dummyRsc1", 9000)
                .addVlmDfn("dummyRsc1", 0, 12*MB)
            .addRscDfn("dummyRsc2", 9001)
                .addVlmDfn("dummyRsc2", 0, 12*MB)
            .addRscDfn("dummyRsc3", 9002)
                .addVlmDfn("dummyRsc3", 0, 12*MB)
            .addRsc("dummyRsc1", "sp1", "stlt1")
            .addRsc("dummyRsc2", "sp1", "stlt1")
            .addRsc("dummyRsc3", "sp1", "stlt2");

        evaluateTest(call);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(1, deployedNodes.size());
        assertEquals("stlt3", deployedNodes.get(0).getName().displayValue);
    }

    @Test
    public void maxThroughputStrategyTest() throws Exception
    {
        enterScope();
        ctrlConf.setProp(ApiConsts.NAMESPC_AUTOPLACER_WEIGHTS + "/FreeSpace", "0");
        ctrlConf.setProp(ApiConsts.NAMESPC_AUTOPLACER_WEIGHTS + "/MaxThroughput", "1");
        commitAndCleanUp(true);

        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            1,
            true,
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED // rsc autoplace
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .setVlmDfnProp(TEST_RSC_NAME, 0, ApiConsts.NAMESPC_SYS_FS + "/"  + ApiConsts.KEY_SYS_FS_BLKIO_THROTTLE_READ, "100")
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100*GB)
                // sp with highest max throughput - but having already some rscs / vlms deployed
                .setStorPoolProp("sp1", ApiConsts.NAMESPC_AUTOPLACER + "/"  + ApiConsts.KEY_AUTOPLACE_MAX_THROUGHPUT, "1000")
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 100*GB)
                .setStorPoolProp("sp1", ApiConsts.NAMESPC_AUTOPLACER + "/"  + ApiConsts.KEY_AUTOPLACE_MAX_THROUGHPUT, "800")
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 100*GB)
                .setStorPoolProp("sp1", ApiConsts.NAMESPC_AUTOPLACER + "/"  + ApiConsts.KEY_AUTOPLACE_MAX_THROUGHPUT, "500")
                .build()

            .addRscDfn("dummyRsc1", 9000)
                .addVlmDfn("dummyRsc1", 0, 12*MB)
                .setVlmDfnProp("dummyRsc1", 0,  ApiConsts.NAMESPC_SYS_FS + "/"  + ApiConsts.KEY_SYS_FS_BLKIO_THROTTLE_READ, "100")
            .addRscDfn("dummyRsc2", 9001)
                .addVlmDfn("dummyRsc2", 0, 12*MB)
                .setVlmDfnProp("dummyRsc2", 0,  ApiConsts.NAMESPC_SYS_FS + "/"  + ApiConsts.KEY_SYS_FS_BLKIO_THROTTLE_READ, "100")
            .addRscDfn("dummyRsc3", 9002)
                .addVlmDfn("dummyRsc3", 0, 12*MB)
                .setVlmDfnProp("dummyRsc3", 0,  ApiConsts.NAMESPC_SYS_FS + "/"  + ApiConsts.KEY_SYS_FS_BLKIO_THROTTLE_READ, "100")
            .addRsc("dummyRsc1", "sp1", "stlt1")
            .addRsc("dummyRsc2", "sp1", "stlt1")
            .addRsc("dummyRsc3", "sp1", "stlt1");

        evaluateTest(call);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
                )
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(1, deployedNodes.size());
        assertEquals("stlt2", deployedNodes.get(0).getName().displayValue);
    }

    @Test
    public void freeSpaceReversedTest() throws Exception
    {
        enterScope();
        ctrlConf.setProp(
            ApiConsts.NAMESPC_AUTOPLACER_WEIGHTS + "/" + ApiConsts.KEY_AUTOPLACE_STRAT_WEIGHT_MAX_FREESPACE,
            "-1"
        );
        commitAndCleanUp(true);

        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            1,
            true,
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED // rsc autoplace
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100*GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 90*GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 50*GB)
                .build();

        evaluateTest(call);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(1, deployedNodes.size());
        assertEquals("stlt3", deployedNodes.get(0).getName().displayValue);
    }

    @Test
    public void preventMixedProviderTest() throws Exception
    {
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            2,
            true,
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED // rsc autoplace
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100*GB, LVM)
                .build()
            .stltBuilder("stlt2") // DO NOT select this
                .addStorPool("sp1", 1*TB, LVM_THIN)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 900 * GB, LVM)
                .build();

        evaluateTest(call);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(2, deployedNodes.size());
        assertEquals("stlt1", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt3", deployedNodes.get(1).getName().displayValue);
    }

    @Test
    public void createRscWithDrbdProxyTest() throws Exception
    {
        enterScope();
        ctrlConf.setProp(ApiConsts.NAMESPC_DRBD_PROXY + "/" + ApiConsts.KEY_DRBD_PROXY_AUTO_ENABLE, "true");
        commitAndCleanUp(true);

        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            3,
            true
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .setNodeProp(ApiConsts.KEY_SITE, "A")
                .addStorPool("sp1", 100*GB, LVM)
                .setExtToolSupported(ExtTools.DRBD_PROXY, true, 42, 42, 9001)
                .build()
            .stltBuilder("stlt2")
                .setNodeProp(ApiConsts.KEY_SITE, "A")
                .addStorPool("sp1", 100*GB, LVM)
                .setExtToolSupported(ExtTools.DRBD_PROXY, true, 42, 42, 9001)
                .build()
            .stltBuilder("stlt3")
                .setNodeProp(ApiConsts.KEY_SITE, "B")
                .addStorPool("sp1", 100*GB, LVM)
                .setExtToolSupported(ExtTools.DRBD_PROXY, true, 42, 42, 9001)
                .build();

        evaluateTest(call, false);

        List<Resource> deployedRscs = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .sorted()
            .collect(Collectors.toList());
        assertEquals(3, deployedRscs.size());

        Resource rsc1 = deployedRscs.get(0);
        Resource rsc2 = deployedRscs.get(1);
        Resource rsc3 = deployedRscs.get(2);

        // expect NO proxy between stlt1 <-> stlt2
        assertNull(rsc1.getAbsResourceConnection(SYS_CTX, rsc2));

        // expect proxy between stlt1 <-> stlt3
        ResourceConnection rscCon13 = rsc1.getAbsResourceConnection(SYS_CTX, rsc3);
        assertNotNull(rscCon13);
        assertNotNull(rscCon13.getPort(SYS_CTX));
        assertTrue(rscCon13.getStateFlags().isSet(SYS_CTX, ResourceConnection.Flags.LOCAL_DRBD_PROXY));

        // expect proxy between stlt2 <-> stlt3
        ResourceConnection rscCon23 = rsc2.getAbsResourceConnection(SYS_CTX, rsc3);
        assertNotNull(rscCon23);
        assertNotNull(rscCon23.getPort(SYS_CTX));
        assertTrue(rscCon23.getStateFlags().isSet(SYS_CTX, ResourceConnection.Flags.LOCAL_DRBD_PROXY));
    }

    @Test
    public void keepFirstCandidateAfterContinuedSearch_Github139Test() throws Exception
    {
        /*
         * Scenario: The Autoplacer.Selector happily finds the first candidate but is unsure
         * if that is the best candidate or not, so it continues searching for a while.
         * When the selector is confident enough that there cannot be any better candidates
         * it simply returns the stored candidate.
         *
         * 139's bug is that the returned "stored" set of candidates was actually the same
         * reference as the Selector's internal "currentCandidateSet", which gets cleared
         * if a new search starts (i.e. "the search continues" after a candidate was found)
         */

        final String testKey = "Aux/test";
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            2,
            true,
            ApiConsts.CREATED,
            ApiConsts.CREATED,
            ApiConsts.CREATED
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .setNodeProp(testKey, "a")
                .addStorPool("sp1", 100*GB, LVM_THIN) // best stor-pool, best candidate
                .build()
            .stltBuilder("stlt2")
                .setNodeProp(testKey, "a")
                // worst stor-pool but the only that can be combined with best candidate
                .addStorPool("sp1", 10*GB, LVM_THIN)
                .build()
            .stltBuilder("stlt3")
                .setNodeProp(testKey, "b")
                // better than the worst, but not as good as best, also not best candidate
                .addStorPool("sp1", 30*GB, LVM_THIN)
                .build()
            .stltBuilder("stlt4")
                .setNodeProp(testKey, "b")
                // better than the worst, but not as good as best, also not best candidate
                .addStorPool("sp1", 30*GB, LVM_THIN)
                .build()
            .stltBuilder("stlt5")
                .setNodeProp(testKey, "c")
                // theoretically combinable to get a score higher than currently best, but
                // practically not combinable to a candidate due to the property-constraint
                .addStorPool("sp1", 90*GB, LVM_THIN)
                .build()
            .addReplicasOnSameNodeProp(testKey)
            .addStorPool("sp1");

        evaluateTest(call);

        List<Resource> deployedRscs = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .sorted()
            .collect(Collectors.toList());

        assertEquals(2, deployedRscs.size());
    }

    @Test
    public void autoPlaceAdditionalTest() throws Exception
    {
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            null,
            true,
            ApiConsts.CREATED, // property set
            ApiConsts.CREATED // rsc autoplace
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100 * GB)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 90 * GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 50 * GB)
                .build()
            .addRsc(TEST_RSC_NAME, "sp1", "stlt1")
            .setAdditionalPlaceCount(1);

        evaluateTest(call);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(2, deployedNodes.size());
    }

    @Test
    public void doNotMixStorPoolsTest() throws Exception
    {
        /*
         * Scenario: We already have a diskful resource in an LVM pool, the
         * autoplace should reject the "best candidate" if it is not an LVM pool.
         */
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            2,
            true,
            ApiConsts.CREATED,
            ApiConsts.CREATED
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100 * GB, LVM_THIN)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 100 * GB, ZFS)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 100 * GB, ZFS_THIN)
                .build()
            .stltBuilder("stlt4")
                .addStorPool("sp1", 10 * GB, LVM)
                .build()
            .stltBuilder("stlt5")
                .addStorPool("sp1", 90 * GB, LVM)
                .build()
            .addRsc(TEST_RSC_NAME, "sp1", "stlt5");
        evaluateTest(call);

        List<Resource> deployedRscs = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .sorted()
            .collect(Collectors.toList());
        assertEquals(2, deployedRscs.size());
        assertEquals("stlt4", deployedRscs.get(0).getNode().getName().displayValue);
        assertEquals("stlt5", deployedRscs.get(1).getNode().getName().displayValue);
    }

    @Test
    public void extToolsTest() throws Exception
    {
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            1,
            true,
            ApiConsts.CREATED,
            ApiConsts.CREATED
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100 * GB, ZFS)
                .setExtToolSupported(ExtTools.ZSTD, true, 1, 0, 0)
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 200 * GB, ZFS) // larger but no ZSTD support
                .build()
            .addRequiredExtTools(ExtTools.ZSTD, 0, 0, 0);
        evaluateTest(call);

        List<Resource> deployedRscs = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .sorted()
            .collect(Collectors.toList());
        assertEquals(1, deployedRscs.size());
        assertEquals("stlt1", deployedRscs.get(0).getNode().getName().displayValue);
    }


    @Test
    public void autoPlaceAllowTargetNodePropTest() throws Exception
    {
        RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
            TEST_RSC_NAME,
            2,
            true,
            ApiConsts.CREATED,
            ApiConsts.CREATED,
            ApiConsts.CREATED
        )
            .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
            .stltBuilder("stlt1")
                .addStorPool("sp1", 100 * GB)
                .setNodeProp(ApiConsts.KEY_AUTOPLACE_ALLOW_TARGET, "false")
                .build()
            .stltBuilder("stlt2")
                .addStorPool("sp1", 90 * GB)
                .build()
            .stltBuilder("stlt3")
                .addStorPool("sp1", 50 * GB)
                .build();

        evaluateTest(call);

        List<Node> deployedNodes = nodesMap.values().stream()
            .flatMap(this::streamResources)
            .filter(
                rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
            )
            .map(rsc -> rsc.getNode())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(2, deployedNodes.size());
        assertEquals("stlt2", deployedNodes.get(0).getName().displayValue);
        assertEquals("stlt3", deployedNodes.get(1).getName().displayValue);
    }

    // @Test
    // public void autoPlaceAllowTargetStorPoolPropTest() throws Exception
    // {
    // RscAutoPlaceApiCall call = new RscAutoPlaceApiCall(
    // TEST_RSC_NAME,
    // 2,
    // true,
    // ApiConsts.CREATED,
    // ApiConsts.CREATED,
    // ApiConsts.CREATED
    // )
    // .addVlmDfn(TEST_RSC_NAME, 0, 1 * GB)
    // .stltBuilder("stlt1")
    // .addStorPool("sp1", 100 * GB)
    // .setStorPoolProp("sp1", ApiConsts.KEY_AUTOPLACE_ALLOW_TARGET, "false")
    // .build()
    // .stltBuilder("stlt2")
    // .addStorPool("sp1", 90 * GB)
    // .build()
    // .stltBuilder("stlt3")
    // .addStorPool("sp1", 50 * GB)
    // .build();
    //
    // evaluateTest(call);
    //
    // List<Node> deployedNodes = nodesMap.values().stream()
    // .flatMap(this::streamResources)
    // .filter(
    // rsc -> rsc.getResourceDefinition().getName().displayValue.equals(TEST_RSC_NAME)
    // )
    // .map(rsc -> rsc.getNode())
    // .sorted()
    // .collect(Collectors.toList());
    //
    // assertEquals(2, deployedNodes.size());
    // assertEquals("stlt2", deployedNodes.get(0).getName().displayValue);
    // assertEquals("stlt3", deployedNodes.get(1).getName().displayValue);
    // }

    private void expectDeployed(
        String storPoolNameStr,
        String rscNameStr,
        String... nodeNameStrs
    )
        throws Exception
    {
        StorPoolName storPoolName = new StorPoolName(storPoolNameStr);
        ResourceName rscName = new ResourceName(rscNameStr);

        for (String nodeNameStr : nodeNameStrs)
        {
            Node node = nodesMap.get(new NodeName(nodeNameStr));
            Resource rsc = node.getResource(GenericDbBase.SYS_CTX, rscName);
            assertNotNull(rsc);

            Iterator<Volume> vlmIt = rsc.iterateVolumes();
            assertTrue(vlmIt.hasNext());

            while (vlmIt.hasNext())
            {
                Volume vlm = vlmIt.next();
                assertEquals(
                    storPoolName,
                    vlm.getAbsResource()
                        .getLayerData(SYS_CTX) // drbd layer
                        .getSingleChild() // storage layer
                        .getVlmProviderObject(vlm.getVolumeDefinition().getVolumeNumber())
                        .getStorPool()
                        .getName()
                        .displayValue
                );
            }
        }
    }

    private void expectNotDeployed(String rscNameStr) throws Exception
    {
        ResourceName rscName = new ResourceName(rscNameStr);

        ResourceDefinition rscDfn = rscDfnMap.get(rscName);

        assertThat(rscDfn.getResourceCount()).isEqualTo(0);
    }


    private ResourceDefinition createRscDfn(String rscNameStr, int tcpPort)
        throws Exception
    {
        LayerPayload payload = new LayerPayload();
        DrbdRscDfnPayload drbdRscDfn = payload.getDrbdRscDfn();
        drbdRscDfn.tcpPort = tcpPort;
        drbdRscDfn.sharedSecret = "NotTellingYou";
        drbdRscDfn.transportType = TransportType.IP;
        ResourceDefinition rscDfn = resourceDefinitionFactory.create(
            BOB_ACC_CTX,
            new ResourceName(rscNameStr),
            null,
            null,
            Arrays.asList(DRBD, STORAGE),
            payload,
            dfltRscGrp
        );

        rscDfnMap.put(rscDfn.getName(), rscDfn);

        return rscDfn;
    }

    private Stream<Resource> streamResources(Node node)
    {
        Stream<Resource> ret;
        try
        {
            ret = node.streamResources(GenericDbBase.SYS_CTX);
        }
        catch (AccessDeniedException exc)
        {
            throw new RuntimeException(exc);
        }
        return ret;
    }

    private class RscAutoPlaceApiCall extends AbsApiCallTester
    {
        private final String rscNameStr;
        private Integer placeCount;
        private Integer additionalPlaceCount;

        private final List<String> doNotPlaceWithRscList = new ArrayList<>();
        private final List<String> nodeNameList = new ArrayList<>();
        private final List<String> storPoolNameList = new ArrayList<>();
        private final List<String> storPoolDisklessNameList = new ArrayList<>();
        private String doNotPlaceWithRscRegexStr = null;

        private final List<String> replicasOnSameNodePropList = new ArrayList<>();
        private final List<String> replicasOnDifferentNodePropList = new ArrayList<>();
        private boolean disklessOnRemaining;
        private List<String> skipAlreadyPlacedOnNodeCheck;
        private boolean skipAlreadyPlacedOnAllNodeCheck = false;

        private final List<DeviceLayerKind> layerStack = new ArrayList<>(Arrays.asList(DRBD, STORAGE));
        private final List<DeviceProviderKind> providerList =
            new ArrayList<>(Arrays.asList(DeviceProviderKind.values()));
        private String disklessType;
        private Map<ExtTools, ExtToolsInfo.Version> requiredExtTools = null;

        RscAutoPlaceApiCall(
            String rscNameStrRef,
            Integer placeCountRef,
            boolean expectDeployment,
            long... expectedRetCodes
        )
        {
            super(
                ApiConsts.MASK_RSC,
                ApiConsts.MASK_CRT,
                expectDeployment ?
                    // When the resources are successfully registered in the DB, the API call handler should try to
                    // deploy them on the satellites. We deliberately cause this to fail. Hence we expect a failure
                    // response after the registration success responses.
                    LongStream.concat(
                        LongStream.of(expectedRetCodes),
                        LongStream.of(ApiConsts.FAIL_UNKNOWN_ERROR)
                    ).toArray() :
                    expectedRetCodes
            );
            rscNameStr = rscNameStrRef;
            placeCount = placeCountRef;
        }

        public RscAutoPlaceApiCall addRequiredExtTools(ExtTools extTool, Integer major, Integer minor, Integer patch)
        {
            if (requiredExtTools == null)
            {
                requiredExtTools = new HashMap<>();
            }
            requiredExtTools.put(extTool, new Version(major, minor, patch));
            return this;
        }

        RscAutoPlaceApiCall setPlaceCount(Integer placeCountRef)
        {
            placeCount = placeCountRef;
            return this;
        }

        RscAutoPlaceApiCall setAdditionalPlaceCount(Integer additionalPlaceCountRef)
        {
            additionalPlaceCount = additionalPlaceCountRef;
            return this;
        }

        public RscAutoPlaceApiCall setProvider(DeviceProviderKind... kinds)
        {
            providerList.clear();
            providerList.addAll(Arrays.asList(kinds));
            return this;
        }

        public RscAutoPlaceApiCall setLayerStack(DeviceLayerKind... kinds)
        {
            layerStack.clear();
            layerStack.addAll(Arrays.asList(kinds));
            return this;
        }

        RscAutoPlaceApiCall setDoNotPlaceWithRegex(String doNotPlaceWithRscRegexStrRef)
        {
            doNotPlaceWithRscRegexStr = doNotPlaceWithRscRegexStrRef;
            return this;
        }

        RscAutoPlaceApiCall addReplicasOnSameNodeProp(String nodePropKey)
        {
            replicasOnSameNodePropList.add(nodePropKey);
            return this;
        }

        RscAutoPlaceApiCall addReplicasOnDifferentNodeProp(String nodePropKey)
        {
            replicasOnDifferentNodePropList.add(nodePropKey);
            return this;
        }

        RscAutoPlaceApiCall addNode(String nodeNameRef)
        {
            nodeNameList.add(nodeNameRef);
            return this;
        }

        RscAutoPlaceApiCall addStorPool(String storPoolNameRef)
        {
            storPoolNameList.add(storPoolNameRef);
            return this;
        }

        RscAutoPlaceApiCall addStorPoolDiskless(String storPoolDisklessNameRef)
        {
            storPoolDisklessNameList.add(storPoolDisklessNameRef);
            return this;
        }

        RscAutoPlaceApiCall doNotPlaceWith(String... doNotPlaceWithRsc)
        {
            doNotPlaceWithRscList.addAll(Arrays.asList(doNotPlaceWithRsc));
            return this;
        }

        RscAutoPlaceApiCall disklessOnRemaining(boolean disklessOnRemainingRef)
        {
            disklessOnRemaining = disklessOnRemainingRef;
            return this;
        }

        RscAutoPlaceApiCall skipAlreadyPlacedOnNodeCheck(List<String> skipAlreadyPlacedOnNodeCheckRef)
        {
            skipAlreadyPlacedOnNodeCheck = skipAlreadyPlacedOnNodeCheckRef;
            return this;
        }

        RscAutoPlaceApiCall setDisklessType(String disklessTypeRef)
        {
            disklessType = disklessTypeRef;
            return this;
        }

        @Override
        public ApiCallRc executeApiCall()
            throws Exception
        {
            ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
            rscAutoPlaceApiCallHandler.autoPlace(
                rscNameStr,
                new AutoSelectFilterApi()
                {
                    @Override
                    public List<String> getNodeNameList()
                    {
                        return nodeNameList;
                    }

                    @Override
                    public List<String> getStorPoolNameList()
                    {
                        return storPoolNameList;
                    }

                    @Override
                    public List<String> getStorPoolDisklessNameList()
                    {
                        return storPoolDisklessNameList;
                    }

                    @Override
                    public List<String> getReplicasOnSameList()
                    {
                        return replicasOnSameNodePropList;
                    }

                    @Override
                    public List<String> getReplicasOnDifferentList()
                    {
                        return replicasOnDifferentNodePropList;
                    }

                    @Override
                    public Integer getReplicaCount()
                    {
                        return placeCount;
                    }

                    @Override
                    public Integer getAdditionalReplicaCount()
                    {
                        return additionalPlaceCount;
                    }

                    @Override
                    public String getDoNotPlaceWithRscRegex()
                    {
                        return doNotPlaceWithRscRegexStr;
                    }

                    @Override
                    public List<String> getDoNotPlaceWithRscList()
                    {
                        return doNotPlaceWithRscList;
                    }

                    @Override
                    public List<DeviceLayerKind> getLayerStackList()
                    {
                        return layerStack;
                    }

                    @Override
                    public List<DeviceProviderKind> getProviderList()
                    {
                        return providerList;
                    }

                    @Override
                    public Boolean getDisklessOnRemaining()
                    {
                        return disklessOnRemaining;
                    }

                    @Override
                    public List<String> skipAlreadyPlacedOnNodeNamesCheck()
                    {
                        return skipAlreadyPlacedOnNodeCheck;
                    }

                    @Override
                    public Boolean skipAlreadyPlacedOnAllNodeCheck()
                    {
                        return skipAlreadyPlacedOnAllNodeCheck;
                    }

                    @Override
                    public String getDisklessType()
                    {
                        return disklessType;
                    }

                    @Override
                    public Map<ExtTools, Version> getRequiredExtTools()
                    {
                        return requiredExtTools;
                    }
                }
            ).contextWrite(contextWrite()).toStream().forEach(apiCallRc::addEntries);
            return apiCallRc;
        }

        SatelliteBuilder stltBuilder(String stltName) throws Exception
        {
            enterScope();

            Node stlt = nodeFactory.create(
                ApiTestBase.BOB_ACC_CTX,
                new NodeName(stltName),
                Node.Type.SATELLITE,
                null
            );

            nodesMap.put(stlt.getName(), stlt);
            StorPoolDefinition dfltDisklessStorPoolDfn =
                storPoolDefinitionRepository.get(SYS_CTX, DFLT_DISKLESS_STOR_POOL_NAME);
            if (dfltDisklessStorPoolDfn == null)
            {
                dfltDisklessStorPoolDfn = storPoolDefinitionFactory.create(
                    BOB_ACC_CTX,
                    DFLT_DISKLESS_STOR_POOL_NAME
                );
            }
            FreeSpaceMgr fsm = freeSpaceMgrFactory.getInstance(
                BOB_ACC_CTX,
                new SharedStorPoolName(stlt.getName(), DFLT_DISKLESS_STOR_POOL_NAME)
            );
            storPoolFactory.create(
                BOB_ACC_CTX,
                stlt,
                dfltDisklessStorPoolDfn,
                DeviceProviderKind.DISKLESS,
                fsm,
                false
            );

            commitAndCleanUp(true);

            return new SatelliteBuilder(this, stlt);
        }

        RscAutoPlaceApiCall addVlmDfn(String rscNameStrRef, int vlmNrRef, long sizeRef) throws Exception
        {
            enterScope();

            ResourceName rscName = new ResourceName(rscNameStrRef);
            ResourceDefinition rscDfn = rscDfnMap.get(rscName);

            volumeDefinitionFactory.create(
                ApiTestBase.BOB_ACC_CTX,
                rscDfn,
                new VolumeNumber(vlmNrRef),
                MINOR_GEN.incrementAndGet(),
                sizeRef,
                null
            );

            commitAndCleanUp(true);

            return this;
        }

        RscAutoPlaceApiCall setVlmDfnProp(String rscNameRef, int vlmNrRef, String propKeyRef, String propValRef)
            throws Exception
        {
            enterScope();
            rscDfnMap.get(new ResourceName(rscNameRef))
                .getVolumeDfn(SYS_CTX, new VolumeNumber(vlmNrRef))
                .getProps(SYS_CTX)
                .setProp(propKeyRef, propValRef);
            commitAndCleanUp(true);
            return this;
        }



        RscAutoPlaceApiCall addRscDfn(String rscNameStrRef, int tcpPortRef) throws Exception
        {
            enterScope();

            createRscDfn(rscNameStrRef, tcpPortRef);

            commitAndCleanUp(true);

            return this;
        }


        RscAutoPlaceApiCall addRsc(String rscNameStrRef, String storPool, String... stltNameStrs) throws Exception
        {
            enterScope();

            Map<String, String> rscPropsMap = new TreeMap<>();
            rscPropsMap.put(ApiConsts.KEY_STOR_POOL_NAME, storPool);
            for (String stltNameStr : stltNameStrs)
            {
                ctrlRscCrtApiHelper.createResourceDb(
                    stltNameStr,
                    rscNameStrRef,
                    0L,
                    rscPropsMap,
                    Collections.emptyList(),
                    null,
                    null,
                    Collections.emptyList(),
                    Resource.DiskfulBy.USER
                );
            }

            commitAndCleanUp(true);

            return this;
        }
    }


    private class SatelliteBuilder
    {
        private final RscAutoPlaceApiCall parent;
        private final Node stlt;
        private final Peer mockedPeer;
        private final ExtToolsManager mockedExtToolsMgr;

        SatelliteBuilder(RscAutoPlaceApiCall parentRef, Node stltRef)
        {
            mockedPeer = Mockito.mock(Peer.class);
            mockedExtToolsMgr = Mockito.mock(ExtToolsManager.class);

            // Fail deployment of the new resources so that the API call handler doesn't wait for the resource to be ready
            Mockito.when(mockedPeer.apiCall(anyString(), any()))
                .thenReturn(Flux.error(new RuntimeException("Deployment deliberately failed")));
            Mockito.when(mockedPeer.isOnline()).thenReturn(true);
            Mockito.when(mockedPeer.getConnectionStatus()).thenReturn(ConnectionStatus.ONLINE);
            Mockito.when(mockedPeer.getExtToolsManager()).thenReturn(mockedExtToolsMgr);

            try
            {
                stltRef.setPeer(GenericDbBase.SYS_CTX, mockedPeer);
            }
            catch (AccessDeniedException exc)
            {
                throw new ImplementationError(exc);
            }

            parent = parentRef;
            stlt = stltRef;

            setSupportedLayers(DeviceLayerKind.values());
            setSupportedProviders(DeviceProviderKind.values());
        }

        public SatelliteBuilder setNodeProp(String key, String value)
            throws Exception
        {
            enterScope();

            stlt.getProps(ApiTestBase.BOB_ACC_CTX).setProp(key, value);

            commitAndCleanUp(true);

            return this;
        }

        SatelliteBuilder addStorPool(String storPoolName, long storPoolSize)
            throws Exception
        {
            return addStorPool(storPoolName, null, storPoolSize, storPoolSize, LVM);
        }

        SatelliteBuilder addStorPool(String storPoolName, long storPoolSize, DeviceProviderKind provider)
            throws Exception
        {
            return addStorPool(storPoolName, null, storPoolSize, storPoolSize, provider);
        }

        SatelliteBuilder addStorPool(
            String storPoolName,
            String freeSpaceMgrName,
            long freeSpace,
            long totalCapacity,
            DeviceProviderKind providerKind
        )
            throws Exception
        {
            enterScope();

            StorPoolDefinition storPoolDfn = storPoolDefinitionRepository.get(
                ApiTestBase.BOB_ACC_CTX,
                new StorPoolName(storPoolName)
            );

            if (storPoolDfn == null)
            {
                storPoolDfn = storPoolDefinitionFactory.create(
                    ApiTestBase.BOB_ACC_CTX,
                    new StorPoolName(storPoolName)
                );

                storPoolDfnMap.put(storPoolDfn.getName(), storPoolDfn);
            }

            FreeSpaceMgr fsm = freeSpaceMgrFactory.getInstance(
                BOB_ACC_CTX,
                freeSpaceMgrName == null ?
                    new SharedStorPoolName(stlt.getName(), storPoolDfn.getName()) :
                    SharedStorPoolName.restoreName(freeSpaceMgrName)
            );

            StorPool storPool = storPoolFactory.create(
                ApiTestBase.BOB_ACC_CTX,
                stlt,
                storPoolDfn,
                providerKind,
                fsm,
                false
            );

            storPool.getFreeSpaceTracker().setCapacityInfo(GenericDbBase.SYS_CTX, freeSpace, totalCapacity);

            commitAndCleanUp(true);

            return this;
        }

        public SatelliteBuilder setStorPoolProp(
            String storPoolNameRef,
            String propKeyRef,
            String propKeyVal
        )
            throws Exception
        {
            enterScope();
            stlt.getStorPool(SYS_CTX, new StorPoolName(storPoolNameRef))
                .getProps(SYS_CTX)
                .setProp(propKeyRef,propKeyVal);
            commitAndCleanUp(true);
            return this;
        }

        public SatelliteBuilder setSupportedLayers(DeviceLayerKind... layers)
        {
            List<DeviceLayerKind> kinds = new ArrayList<>(Arrays.asList(DeviceLayerKind.values()));
            for (DeviceLayerKind supportedLayer : layers)
            {
                Mockito.when(mockedExtToolsMgr.isLayerSupported(supportedLayer)).thenReturn(true);
                kinds.remove(supportedLayer);
            }
            for (DeviceLayerKind unsupportedLayer : kinds)
            {
                Mockito.when(mockedExtToolsMgr.isLayerSupported(unsupportedLayer)).thenReturn(false);
            }
            Mockito.when(mockedExtToolsMgr.getSupportedLayers()).thenReturn(new TreeSet<>(Arrays.asList(layers)));
            return this;
        }

        public SatelliteBuilder setSupportedProviders(DeviceProviderKind... providers)
        {
            List<DeviceProviderKind> kinds = new ArrayList<>(Arrays.asList(DeviceProviderKind.values()));
            for (DeviceProviderKind supportedProvider : providers)
            {
                Mockito.when(mockedExtToolsMgr.isProviderSupported(supportedProvider)).thenReturn(true);
                kinds.remove(supportedProvider);
            }
            for (DeviceProviderKind unsupportedProviderLayer : providers)
            {
                Mockito.when(mockedExtToolsMgr.isProviderSupported(unsupportedProviderLayer)).thenReturn(false);
            }
            Mockito.when(mockedExtToolsMgr.getSupportedProviders()).thenReturn(new TreeSet<>(Arrays.asList(providers)));
            return this;
        }

        public SatelliteBuilder setExtToolSupported(
            ExtTools tool,
            boolean supported,
            int majorVer,
            int minorVer,
            int patchVer,
            String... reasonsNotSupported
        )
        {
            Mockito.when(mockedExtToolsMgr.getExtToolInfo(tool)).thenReturn(
                new ExtToolsInfo(tool, supported, majorVer, minorVer, patchVer, Arrays.asList(reasonsNotSupported))
            );
            return this;
        }

        public RscAutoPlaceApiCall build()
        {
            return parent;
        }
    }
}
