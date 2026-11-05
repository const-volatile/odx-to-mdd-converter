/*
 * Copyright (c) 2026 The Contributors to Eclipse OpenSOVD (see CONTRIBUTORS)
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import dataformat.vehicle.DiagnosticLayerKind
import dataformat.vehicle.LogicalLink
import dataformat.vehicle.VehicleData
import org.eclipse.persistence.jaxb.JAXBContextFactory
import schema.odx.ODX
import java.io.File
import java.nio.ByteBuffer
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleDataIntegrationTest {
    private val converter =
        FileConverter(
            Logger.getLogger("vehicle-data-integration-test"),
            JAXBContextFactory.createContext(arrayOf(ODX::class.java), null),
        )

    @Test
    fun `companion preserves independent links to existing ECU and functional group MDDs`() {
        withTestDirectory { directory ->
            val (ecuPdx, groupsPdx) = packDiagnosticPdxFiles(directory)
            val visPdx = File(directory, "vehicle.pdx")
            packPdxFiles(visPdx, mapOf("vehicle.odx-v" to vehicleDocument()))
            val ecuMdd = File(directory, "ecu.mdd")
            val groupsMdd = File(directory, "groups.mdd")
            val options = ConverterOptions(skipSigning = true)
            converter.convert(ecuPdx, ecuMdd, options, mutableListOf())
            converter.convert(groupsPdx, groupsMdd, options, mutableListOf())
            val originalEcuMdd = ecuMdd.readBytes()
            val originalGroupsMdd = groupsMdd.readBytes()

            val companion = File(directory, "vehicle.vehicle-data")
            converter.writeVehicleData(listOf(visPdx, ecuPdx, groupsPdx), companion)

            val buffer = ByteBuffer.wrap(companion.readBytes())
            assertTrue(VehicleData.VehicleDataBufferHasIdentifier(buffer))
            val data = VehicleData.getRootAsVehicleData(buffer)
            val vehicles =
                (0 until data.vehicleInformationsLength)
                    .map { assertNotNull(data.vehicleInformations(it)) }
                    .associateBy { it.shortName }
            assertEquals(setOf("TestVehicle", "BodyVehicle"), vehicles.keys)
            assertTrue(vehicles.values.all { it.sourceShortName == "VehicleSpec" })
            val vehicle = vehicles.getValue("TestVehicle")
            val links =
                (0 until vehicle.logicalLinksLength)
                    .map { assertNotNull(vehicle.logicalLinks(it)) }
                    .associateBy { it.id }
            assertEquals(setOf("front", "rear", "gateway"), links.keys)
            val front = links.getValue("front")
            val rear = links.getValue("rear")
            val gateway = links.getValue("gateway")
            val bodyVehicle = vehicles.getValue("BodyVehicle")
            assertEquals(1, bodyVehicle.logicalLinksLength)
            val body = assertNotNull(bodyVehicle.logicalLinks(0))
            assertEquals("Front", front.shortName)
            assertEquals("Rear", rear.shortName)
            assertEquals("Body", body.shortName)
            assertFalse(front.isGateway)
            assertTrue(gateway.isGateway)
            assertEquals("Routing", gateway.gatewaySemantic)
            assertEquals(0, gateway.targetsLength)
            assertEquals(1, front.gatewayLinksLength)
            val gatewayRef = assertNotNull(front.gatewayLinks(0))
            assertEquals("VehicleSpec", gatewayRef.sourceShortName)
            assertEquals(gateway.id, gatewayRef.id)

            assertContentEquals(originalEcuMdd, ecuMdd.readBytes())
            assertContentEquals(originalGroupsMdd, groupsMdd.readBytes())
            val ecu = readMddEcuData(ecuMdd)
            val base =
                (0 until ecu.variantsLength)
                    .map { assertNotNull(ecu.variants(it)) }
                    .single { it.isBaseVariant }
                    .let { assertNotNull(it.diagLayer) }
            val groups = readMddEcuData(groupsMdd)
            assertEquals(1, groups.functionalGroupsLength)
            val group = assertNotNull(groups.functionalGroups(0)?.diagLayer)
            assertEquals(Triple(ecu.ecuName, DiagnosticLayerKind.BASE_VARIANT, base.shortName), targetKey(front))
            assertEquals(targetKey(front), targetKey(rear))
            assertEquals(Triple(groups.ecuName, DiagnosticLayerKind.FUNCTIONAL_GROUP, group.shortName), targetKey(body))
            val originalTimeout =
                (0 until base.comParamRefsLength)
                    .map { assertNotNull(base.comParamRefs(it)) }
                    .single { it.comParam?.shortName == "CP_Timeout" }
            assertEquals("3000", originalTimeout.simpleValue?.value)

            for ((link, timeout) in listOf(front to "500", rear to "700", body to "4000")) {
                assertEquals("UDS_on_CAN", link.protocol)
                assertEquals("UDS_CAN_Stack", link.protocolStack)
                assertEquals(if (link.id == "body") "body_bus" else "can_bus", link.physicalLinkId)
                assertEquals("VehicleSpec", link.physicalLinkSourceShortName)
                assertEquals("CAN", link.physicalLinkType)
                val param = parameter(link, "CP_Timeout")
                assertEquals("CANParameters", param.definitionDocument)
                assertEquals("cp_timeout", param.definitionId)
                assertEquals(timeout, param.value?.simpleValue)
            }
            val timing = assertNotNull(parameter(front, "CCP_TimingParams").value)
            assertEquals(1, timing.complexValuesLength)
            val p2Timeout = assertNotNull(timing.complexValues(0))
            assertEquals("CP_P2Timeout", p2Timeout.shortName)
            assertEquals("100", p2Timeout.simpleValue)
            assertNull(parameter(front, "CP_BaudRate").value)
        }
    }

    @Test
    fun `unresolved link preserves an existing companion`() {
        withTestDirectory { directory ->
            val (ecuPdx, groupsPdx) = packDiagnosticPdxFiles(directory)
            val visPdx = File(directory, "vehicle.pdx")
            val companion = File(directory, "vehicle.vehicle-data")
            val inputs = listOf(visPdx, ecuPdx, groupsPdx)
            packPdxFiles(visPdx, mapOf("vehicle.odx-v" to vehicleDocument()))
            converter.writeVehicleData(inputs, companion)
            val original = companion.readBytes()

            val broken = vehicleDocument(baseId = "missing_variant")
            packPdxFiles(visPdx, mapOf("vehicle.odx-v" to broken))

            val error = assertFailsWith<OdxResolutionException> { converter.writeVehicleData(inputs, companion) }
            assertTrue(error.message.orEmpty().contains("missing_variant"))
            assertContentEquals(original, companion.readBytes())
        }
    }

    private fun targetKey(link: LogicalLink): Triple<String?, Byte, String?> {
        assertEquals(1, link.targetsLength)
        val target = assertNotNull(link.targets(0))
        return Triple(target.databaseId, target.kind, target.shortName)
    }

    private fun parameter(
        link: LogicalLink,
        shortName: String,
    ) = (0 until link.comParamsLength)
        .map { assertNotNull(link.comParams(it)) }
        .single { it.shortName == shortName }

    private fun packDiagnosticPdxFiles(directory: File): Pair<File, File> {
        val fixtureDirectory = File(javaClass.getResource("/synthetic-odx")!!.toURI())
        val fixtures =
            fixtureDirectory
                .listFiles()!!
                .filter { it.isFile }
                .sortedBy { it.name }
                .associate { it.name to it.readText() }
        val ecu = File(directory, "ecu.pdx")
        val groups = File(directory, "groups.pdx")
        val groupFile = "functional-group.odx-d"
        val comParamFile = "comparam-subset.odx-cs"
        val comParams = fixtures.getValue(comParamFile).replace("<PHYSICAL-DEFAULT-VALUE>500000</PHYSICAL-DEFAULT-VALUE>", "")
        packPdxFiles(ecu, (fixtures - groupFile) + (comParamFile to comParams))
        // The shared FG archive is independently convertible, with only the group definition.
        val shared = fixtures.getValue(groupFile).replace(Regex("<PARENT-REFS>.*?</PARENT-REFS>", RegexOption.DOT_MATCHES_ALL), "")
        packPdxFiles(groups, mapOf(groupFile to shared))
        return ecu to groups
    }

    private fun packPdxFiles(
        output: File,
        files: Map<String, String>,
    ) {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            files.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun withTestDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("vehicle-data-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun vehicleDocument(baseId: String = "bv_synth_ecu"): String {
        val base = "<BASE-VARIANT-REF ID-REF=\"$baseId\" DOCREF=\"SynthECU\" DOCTYPE=\"LAYER\"/>"
        val group = "<FUNCTIONAL-GROUP-REF ID-REF=\"fg_shared_diag\" DOCREF=\"FuncGroupContainer\" DOCTYPE=\"LAYER\"/>"
        val ecuLinks =
            listOf(
                logicalLink("front", "Front", base, timeout = "500", gatewayId = "gateway"),
                logicalLink("rear", "Rear", base, timeout = "700"),
                gatewayLink(),
            )
        val bodyLinks = listOf(logicalLink("body", "Body", group, physicalId = "body_bus"))
        return """
            <ODX MODEL-VERSION="2.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <VEHICLE-INFO-SPEC ID="vis"><SHORT-NAME>VehicleSpec</SHORT-NAME>
                <VEHICLE-INFORMATIONS>
                  ${vehicleInformation("TestVehicle", "can_bus", ecuLinks)}
                  ${vehicleInformation("BodyVehicle", "body_bus", bodyLinks)}
                </VEHICLE-INFORMATIONS>
              </VEHICLE-INFO-SPEC>
            </ODX>
            """.trimIndent()
    }

    private fun vehicleInformation(
        name: String,
        physicalId: String,
        links: List<String>,
    ): String =
        """
        <VEHICLE-INFORMATION><SHORT-NAME>$name</SHORT-NAME>
          <VEHICLE-CONNECTORS><VEHICLE-CONNECTOR><SHORT-NAME>OBD</SHORT-NAME>
            <VEHICLE-CONNECTOR-PINS><VEHICLE-CONNECTOR-PIN ID="${physicalId}_pin" TYPE="HI">
              <SHORT-NAME>CAN_HI</SHORT-NAME><PIN-NUMBER>6</PIN-NUMBER>
            </VEHICLE-CONNECTOR-PIN></VEHICLE-CONNECTOR-PINS>
          </VEHICLE-CONNECTOR></VEHICLE-CONNECTORS>
          <LOGICAL-LINKS>${links.joinToString("\n")}</LOGICAL-LINKS>
          <PHYSICAL-VEHICLE-LINKS><PHYSICAL-VEHICLE-LINK ID="$physicalId" TYPE="CAN">
            <SHORT-NAME>CAN</SHORT-NAME>
            <VEHICLE-CONNECTOR-PIN-REFS><VEHICLE-CONNECTOR-PIN-REF ID-REF="${physicalId}_pin"/></VEHICLE-CONNECTOR-PIN-REFS>
            ${timeoutOverride("4000")}
          </PHYSICAL-VEHICLE-LINK></PHYSICAL-VEHICLE-LINKS>
        </VEHICLE-INFORMATION>
        """.trimIndent()

    private fun logicalLink(
        id: String,
        shortName: String,
        target: String,
        timeout: String? = null,
        physicalId: String = "can_bus",
        gatewayId: String? = null,
    ): String {
        val gatewayRefs =
            gatewayId
                ?.let {
                    "<GATEWAY-LOGICAL-LINK-REFS><GATEWAY-LOGICAL-LINK-REF ID-REF=\"$it\"/></GATEWAY-LOGICAL-LINK-REFS>"
                }.orEmpty()
        return """
            <LOGICAL-LINK xsi:type="MEMBER-LOGICAL-LINK" ID="$id"><SHORT-NAME>$shortName</SHORT-NAME>
              $gatewayRefs
              <PHYSICAL-VEHICLE-LINK-REF ID-REF="$physicalId"/>
              <PROTOCOL-REF ID-REF="proto_uds" DOCREF="ProtocolContainer" DOCTYPE="LAYER"/>
              $target
              ${timeout?.let(::timeoutOverride).orEmpty()}
              <PROT-STACK-SNREF SHORT-NAME="UDS_CAN_Stack"/>
            </LOGICAL-LINK>
            """.trimIndent()
    }

    private fun gatewayLink(): String =
        """
        <LOGICAL-LINK xsi:type="GATEWAY-LOGICAL-LINK" ID="gateway"><SHORT-NAME>Gateway</SHORT-NAME>
          <PHYSICAL-VEHICLE-LINK-REF ID-REF="can_bus"/>
          <PROTOCOL-REF ID-REF="proto_uds" DOCREF="ProtocolContainer" DOCTYPE="LAYER"/>
          <PROT-STACK-SNREF SHORT-NAME="UDS_CAN_Stack"/>
          <SEMANTIC>Routing</SEMANTIC>
        </LOGICAL-LINK>
        """.trimIndent()

    private fun timeoutOverride(value: String): String =
        """
        <LINK-COMPARAM-REFS>
          <LINK-COMPARAM-REF ID-REF="cp_timeout" DOCREF="CANParameters" DOCTYPE="COMPARAM-SUBSET">
            <SIMPLE-VALUE>$value</SIMPLE-VALUE>
          </LINK-COMPARAM-REF>
          <LINK-COMPARAM-REF ID-REF="cp_baudrate" DOCREF="CANParameters" DOCTYPE="COMPARAM-SUBSET"/>
        </LINK-COMPARAM-REFS>
        """.trimIndent()
}
