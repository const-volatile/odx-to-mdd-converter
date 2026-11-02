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

import dataformat.EcuData
import dataformat.Variant
import jakarta.xml.bind.JAXBContext
import schema.odx.COMPLEXVALUE
import schema.odx.DIAGSERVICE
import schema.odx.ODX
import schema.odx.SIMPLEVALUE
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.transform.stream.StreamSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogicalLinkComparamResolverTest {
    @Test
    fun `without VIS the original group is returned unchanged`() {
        val source = group()

        assertSame(source, source.resolveLogicalLinkComparams())
        assertEquals(
            "20",
            source.basevariants
                .single()
                .comparamrefs.comparamref
                .single()
                .simplevalue.value,
        )
    }

    @Test
    fun `logical overrides win over physical and layer values while inherited parameters survive`() {
        val source =
            group(
                logicalLink("Front", overrides = simpleOverride("cp_timeout", "40")),
                physicalOverrides = simpleOverride("cp_timeout", "30"),
            )
        val original = source.basevariants.single()

        val resolved = source.resolveLogicalLinkComparams()
        val refs =
            resolved.basevariants
                .single()
                .comparamrefs.comparamref

        assertNotSame(source, resolved)
        assertEquals("ECU", resolved.basevariants.single().shortname)
        assertEquals("bv_ecu", resolved.basevariants.single().id)
        assertEquals("40", refs.single { it.idref == "cp_timeout" }.simplevalue.value)
        assertEquals("3", refs.single { it.idref == "cp_retry" }.simplevalue.value)
        assertTrue(refs.all { it.protocolsnref.shortname == "UDS_on_CAN" })
        assertTrue(refs.all { it.protstacksnref.shortname == "CAN_Stack" })
        assertEquals(
            "20",
            original.comparamrefs.comparamref
                .single()
                .simplevalue.value,
        )
        assertEquals(1, original.comparamrefs.comparamref.size)
    }

    @Test
    fun `identical overrides reuse one BV regardless of reference order and description`() {
        val source =
            group(
                logicalLink(
                    "Front",
                    overrides = simpleOverride("cp_timeout", "40") + simpleOverride("cp_retry", "4"),
                ),
                logicalLink(
                    "Rear",
                    overrides =
                        simpleOverride("cp_retry", "4", "A different description") +
                            simpleOverride("cp_timeout", "40"),
                ),
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(1, resolved.basevariants.size)
        assertEquals(
            mapOf("cp_timeout" to "40", "cp_retry" to "4"),
            resolved.basevariants
                .single()
                .comparamrefs.comparamref
                .associate { it.idref to it.simplevalue.value },
        )
    }

    @Test
    fun `explicit baseline value and no override share the same effective BV`() {
        val source =
            group(
                logicalLink("Front"),
                logicalLink("Rear", overrides = simpleOverride("cp_timeout", "20")),
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(1, resolved.basevariants.size)
        assertEquals(
            "20",
            resolved.basevariants
                .single()
                .comparamrefs.comparamref
                .single { it.idref == "cp_timeout" }
                .simplevalue.value,
        )
    }

    @Test
    fun `different effective BV values produce uniquely named copies and repeated values are reused`() {
        val source =
            group(
                logicalLink("Front", overrides = simpleOverride("cp_timeout", "40")),
                logicalLink("Rear", overrides = simpleOverride("cp_timeout", "50")),
                logicalLink("FrontAgain", overrides = simpleOverride("cp_timeout", "40")),
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(2, resolved.basevariants.size)
        assertEquals(listOf("ECU", "ECU__Rear"), resolved.basevariants.map { it.shortname })
        assertEquals(
            2,
            resolved.basevariants
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(
            listOf("40", "50"),
            resolved.basevariants.map { bv ->
                bv.comparamrefs.comparamref
                    .single { it.idref == "cp_timeout" }
                    .simplevalue.value
            },
        )
        assertEquals(
            "20",
            source.basevariants
                .single()
                .comparamrefs.comparamref
                .single()
                .simplevalue.value,
        )
        assertTrue(
            resolved.basevariants.all {
                it.diagcomms.diagcommproxy
                    .filterIsInstance<DIAGSERVICE>()
                    .single()
                    .shortname == "ReadStatus"
            },
        )
    }

    @Test
    fun `functional links with different settings copy their shared FG`() {
        val source =
            group(
                logicalLink("Powertrain", target = "FUNCTIONAL-GROUP", overrides = simpleOverride("cp_timeout", "100")),
                logicalLink("Chassis", target = "FUNCTIONAL-GROUP", overrides = simpleOverride("cp_timeout", "200")),
                logicalLink("Body", target = "FUNCTIONAL-GROUP", overrides = simpleOverride("cp_timeout", "100")),
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(2, resolved.functionalGroups.size)
        assertEquals(listOf("UDS_Functions", "UDS_Functions__Chassis"), resolved.functionalGroups.map { it.shortname })
        assertEquals(
            2,
            resolved.functionalGroups
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(
            listOf("100", "200"),
            resolved.functionalGroups.map { fg ->
                fg.comparamrefs.comparamref
                    .single { it.idref == "cp_timeout" }
                    .simplevalue.value
            },
        )
        assertEquals(
            "25",
            source.functionalGroups
                .single()
                .comparamrefs.comparamref
                .single()
                .simplevalue.value,
        )
        assertEquals(1, resolved.basevariants.size)
        assertTrue(
            resolved.functionalGroups.all {
                it.diagcomms.diagcommproxy
                    .filterIsInstance<DIAGSERVICE>()
                    .single()
                    .shortname ==
                    "TesterPresent"
            },
        )
    }

    @Test
    fun `nested complex override values distinguish contexts by content`() {
        fun complexOverride(address: String): String =
            """
            <LINK-COMPARAM-REF ID-REF="cp_unique" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET">
              <COMPLEX-VALUE><COMPLEX-VALUE><SIMPLE-VALUE>$address</SIMPLE-VALUE><SIMPLE-VALUE>7</SIMPLE-VALUE></COMPLEX-VALUE></COMPLEX-VALUE>
            </LINK-COMPARAM-REF>
            """.trimIndent()

        val source =
            group(
                logicalLink("Front", overrides = complexOverride("4096")),
                logicalLink("Rear", overrides = complexOverride("4097")),
                logicalLink("FrontAgain", overrides = complexOverride("4096")),
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(2, resolved.basevariants.size)
        val addresses =
            resolved.basevariants.map { bv ->
                val value =
                    bv.comparamrefs.comparamref
                        .single { it.idref == "cp_unique" }
                        .complexvalue
                val row = value.simplevalueOrCOMPLEXVALUE.single() as COMPLEXVALUE
                (row.simplevalueOrCOMPLEXVALUE.first() as SIMPLEVALUE).value
            }
        assertEquals(listOf("4096", "4097"), addresses)
        assertTrue(
            source.basevariants
                .single()
                .comparamrefs.comparamref
                .none { it.idref == "cp_unique" },
        )
    }

    @Test
    fun `generated copy names do not collide with existing layer names`() {
        val source =
            group(
                logicalLink("Front", overrides = simpleOverride("cp_timeout", "40")),
                logicalLink("Rear", overrides = simpleOverride("cp_timeout", "50")),
                extraVariants = "<BASE-VARIANT ID=\"bv_existing\"><SHORT-NAME>ECU__Rear</SHORT-NAME></BASE-VARIANT>",
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(3, resolved.basevariants.size)
        assertEquals(
            3,
            resolved.basevariants
                .map { it.shortname }
                .toSet()
                .size,
        )
        assertEquals(
            3,
            resolved.basevariants
                .map { it.id }
                .toSet()
                .size,
        )
        assertTrue(resolved.basevariants.any { it.id == "bv_existing" && it.shortname == "ECU__Rear" })
    }

    @Test
    fun `unresolved link target fails with context and leaves source untouched`() {
        val source =
            group(
                logicalLink("Broken", overrides = simpleOverride("cp_timeout", "40")).replace("bv_ecu", "missing_bv"),
            )

        val error = assertFailsWith<OdxResolutionException> { source.resolveLogicalLinkComparams() }

        assertTrue(error.message!!.contains("Broken"))
        assertTrue(error.message!!.contains("missing_bv"))
        assertEquals(
            "20",
            source.basevariants
                .single()
                .comparamrefs.comparamref
                .single()
                .simplevalue.value,
        )
    }

    @Test
    fun `lenient conversion skips broken links without discarding valid contexts`() {
        val source =
            group(
                logicalLink("Front", overrides = simpleOverride("cp_timeout", "40")),
                logicalLink("Broken", overrides = simpleOverride("cp_timeout", "50")).replace("bv_ecu", "missing_bv"),
                options = ConverterOptions(lenient = true),
            )

        val resolved = source.resolveLogicalLinkComparams()

        assertEquals(1, resolved.basevariants.size)
        assertEquals(
            "40",
            resolved.basevariants
                .single()
                .comparamrefs.comparamref
                .single { it.idref == "cp_timeout" }
                .simplevalue.value,
        )
    }

    @Test
    fun `unresolved communication parameter definitions are rejected before publishing a profile`() {
        val source = group(logicalLink("BrokenParameter", overrides = simpleOverride("cp_missing", "40")))

        val error = assertFailsWith<OdxResolutionException> { source.resolveLogicalLinkComparams() }

        assertTrue(error.message!!.contains("cp_missing"))
        assertEquals(
            "20",
            source.basevariants
                .single()
                .comparamrefs.comparamref
                .single()
                .simplevalue.value,
        )
    }

    @Test
    fun `ambiguous inherited protocols require an explicit link protocol`() {
        val diagnosticXml =
            diagnostics("")
                .replace(
                    "</PROTOCOLS>",
                    "<PROTOCOL ID=\"protocol_doip\"><SHORT-NAME>UDS_on_DoIP</SHORT-NAME></PROTOCOL></PROTOCOLS>",
                ).replace(
                    "</PARENT-REFS>",
                    "<PARENT-REF xsi:type=\"PROTOCOL-REF\" ID-REF=\"protocol_doip\" DOCREF=\"Diagnostics\" DOCTYPE=\"LAYER\"/></PARENT-REFS>",
                )
        val link =
            logicalLink("Ambiguous", overrides = simpleOverride("cp_timeout", "40"))
                .replace(Regex("<PROTOCOL-REF[^>]*/>"), "")
        val files =
            mapOf(
                "diagnostics.odx-d" to diagnosticXml,
                "parameters.odx-cs" to parameters,
                "vehicle.odx-v" to vehicle(link, ""),
            )
        val source = parseGroup(files)

        val error = assertFailsWith<OdxResolutionException> { source.resolveLogicalLinkComparams() }

        assertTrue(error.message!!.contains("Ambiguous"))
        assertTrue(error.message!!.contains("PROTOCOL-REF"))
        val lenientSource = parseGroup(files, ConverterOptions(lenient = true))
        assertSame(lenientSource, lenientSource.resolveLogicalLinkComparams())
    }

    @Test
    fun `links without overrides retain multiple protocol parameter sets without selecting a protocol`() {
        val doipProtocol =
            """
            <PROTOCOL ID="protocol_doip"><SHORT-NAME>UDS_on_DoIP</SHORT-NAME>
              <COMPARAM-REFS><COMPARAM-REF ID-REF="cp_timeout" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET">
                <SIMPLE-VALUE>60</SIMPLE-VALUE><PROTOCOL-SNREF SHORT-NAME="UDS_on_DoIP"/>
                <PROT-STACK-SNREF SHORT-NAME="DoIP_Stack"/>
              </COMPARAM-REF></COMPARAM-REFS>
            </PROTOCOL>
            """.trimIndent()
        val diagnosticXml =
            diagnostics("")
                .replace("</PROTOCOLS>", "$doipProtocol</PROTOCOLS>")
                .replace(
                    "</PARENT-REFS>",
                    "<PARENT-REF xsi:type=\"PROTOCOL-REF\" ID-REF=\"protocol_doip\" DOCREF=\"Diagnostics\" DOCTYPE=\"LAYER\"/></PARENT-REFS>",
                )
        val link = logicalLink("Both").replace(Regex("<PROTOCOL-REF[^>]*/>"), "")
        val source =
            parseGroup(
                mapOf(
                    "diagnostics.odx-d" to diagnosticXml,
                    "parameters.odx-cs" to parameters,
                    "vehicle.odx-v" to vehicle(link, ""),
                ),
            )

        val resolved = source.resolveLogicalLinkComparams()
        val refs =
            resolved.basevariants
                .single()
                .comparamrefs.comparamref
                .filter { it.idref == "cp_timeout" }

        assertEquals(
            mapOf("UDS_on_CAN" to "20", "UDS_on_DoIP" to "60"),
            refs.associate { it.protocolsnref.shortname to it.simplevalue.value },
        )
        assertEquals(1, resolved.basevariants.size)
    }

    @Test
    fun `same named CAN and DoIP complex parameters retain their separate definitions and shapes`() {
        val canRef =
            """
            <COMPARAM-REF ID-REF="cp_unique" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET">
              <COMPLEX-VALUE><SIMPLE-VALUE>1792</SIMPLE-VALUE><SIMPLE-VALUE>1800</SIMPLE-VALUE></COMPLEX-VALUE>
              <PROTOCOL-SNREF SHORT-NAME="UDS_on_CAN"/><PROT-STACK-SNREF SHORT-NAME="CAN_Stack"/>
            </COMPARAM-REF>
            """.trimIndent()
        val diagnosticXml =
            diagnostics("")
                .replace("</COMPARAM-REFS>", "$canRef</COMPARAM-REFS>")
                .replace(
                    "</PROTOCOLS>",
                    "<PROTOCOL ID=\"protocol_doip\"><SHORT-NAME>UDS_on_DoIP</SHORT-NAME></PROTOCOL></PROTOCOLS>",
                )
        val doipOverride =
            """
            <LINK-COMPARAM-REF ID-REF="cp_unique" DOCREF="EthernetParameters" DOCTYPE="COMPARAM-SUBSET">
              <COMPLEX-VALUE><SIMPLE-VALUE>4096</SIMPLE-VALUE></COMPLEX-VALUE>
            </LINK-COMPARAM-REF>
            """.trimIndent()
        val link =
            logicalLink("Ethernet", overrides = doipOverride)
                .replace("ID-REF=\"protocol_can\"", "ID-REF=\"protocol_doip\"")
                .replace("CAN_Stack", "DoIP_Stack")

        fun member(
            id: String,
            name: String,
        ): String =
            """
            <COMPARAM ID="$id" PARAM-CLASS="UNIQUE-ID" CPTYPE="STANDARD" CPUSAGE="ECU-COMM">
              <SHORT-NAME>$name</SHORT-NAME>
            </COMPARAM>
            """.trimIndent()
        val canParameters =
            parameters.replace(
                "</COMPLEX-COMPARAM>",
                member("can_request", "CP_CanPhysReqId") + member("can_response", "CP_CanRespUSDTId") + "</COMPLEX-COMPARAM>",
            )
        val ethernetParameters =
            parameters
                .replace("<SHORT-NAME>Parameters</SHORT-NAME>", "<SHORT-NAME>EthernetParameters</SHORT-NAME>")
                .replace("</COMPLEX-COMPARAM>", member("doip_address", "CP_DoIPLogicalEcuAddress") + "</COMPLEX-COMPARAM>")
        val source =
            parseGroup(
                mapOf(
                    "diagnostics.odx-d" to diagnosticXml,
                    "parameters.odx-cs" to canParameters,
                    "ethernet.odx-cs" to ethernetParameters,
                    "vehicle.odx-v" to vehicle(link, ""),
                ),
            )

        val resolved = source.resolveLogicalLinkComparams()
        val refs =
            resolved.basevariants
                .single()
                .comparamrefs.comparamref
                .filter { it.idref == "cp_unique" }

        assertEquals(2, refs.size)
        assertEquals(
            mapOf("UDS_on_CAN" to 2, "UDS_on_DoIP" to 1),
            refs.associate { it.protocolsnref.shortname to it.complexvalue.simplevalueOrCOMPLEXVALUE.size },
        )
        assertEquals(setOf("Parameters", "EthernetParameters"), refs.map { it.docref }.toSet())
        assertEquals("DoIP_Stack", refs.single { it.protocolsnref.shortname == "UDS_on_DoIP" }.protstacksnref.shortname)
        assertEquals(
            mapOf("UDS_on_CAN" to 2, "UDS_on_DoIP" to 1),
            refs.associate { it.protocolsnref.shortname to resolved.resolveComplexComparam(it)!!.comparamOrCOMPLEXCOMPARAM.size },
        )
    }

    @Test
    fun `resolved contexts serialize as ordinary MDD layers with services and parent references`() {
        val source = parseGroup(syntheticWithVis())
        val resolved = source.resolveLogicalLinkComparams()

        val bytes = DatabaseWriter(Logger.getLogger("logical-link-test"), resolved, ConverterOptions()).createEcuData()
        val native = EcuData.getRootAsEcuData(ByteBuffer.wrap(bytes))
        val variants = (0 until native.variantsLength).map { native.variants(it)!! }
        val baseVariants = variants.filter { it.isBaseVariant }
        val groups = (0 until native.functionalGroupsLength).map { native.functionalGroups(it)!! }

        assertEquals(listOf("SynthECU", "SynthECU__Rear"), baseVariants.map { it.diagLayer!!.shortName })
        assertEquals(listOf("SharedDiagnostics", "SharedDiagnostics__Body"), groups.map { it.diagLayer!!.shortName })
        assertEquals(
            listOf("500", "700", "900", "1100"),
            (baseVariants.map { it.diagLayer!! } + groups.map { it.diagLayer!! }).map { layer ->
                val ref =
                    (0 until layer.comParamRefsLength)
                        .map { layer.comParamRefs(it)!! }
                        .single { it.comParam!!.shortName == "CP_Timeout" }
                assertEquals("UDS_on_CAN", ref.protocol!!.diagLayer!!.shortName)
                assertEquals("UDS_CAN_Stack", ref.protStack!!.shortName)
                ref.simpleValue!!.value
            },
        )
        assertTrue(baseVariants.all { it.diagLayer!!.diagServicesLength == 5 })
        assertTrue(groups.all { it.diagLayer!!.diagServicesLength == 1 })
        assertTrue(baseVariants.all { it.parentRefsLength > 0 })
        assertTrue(groups.all { it.parentRefsLength > 0 })
        val ecuVariant = variants.single { !it.isBaseVariant }
        val parent = ecuVariant.parentRefs(0)!!.ref(Variant()) as Variant
        assertEquals("SynthECU", parent.diagLayer!!.shortName)
        assertEquals(
            "3000",
            source.basevariants
                .single()
                .comparamrefs.comparamref
                .single { it.idref == "cp_timeout" }
                .simplevalue.value,
        )
    }

    @Test
    fun `PDX conversion applies logical link contexts only when the option is enabled`() {
        val tempDirectory = createTempDirectory("logical-link-converter-test").toFile()
        try {
            val input = File(tempDirectory, "logical-links.pdx")
            ZipOutputStream(input.outputStream().buffered()).use { zip ->
                syntheticWithVis().forEach { (filename, xml) ->
                    zip.putNextEntry(ZipEntry(filename))
                    zip.write(xml.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val converter = FileConverter(Logger.getLogger("logical-link-test"), context)
            val originalOutput = File(tempDirectory, "original.mdd")
            converter.convert(input, originalOutput, ConverterOptions(skipSigning = true), mutableListOf())
            val original = readMddEcuData(originalOutput)
            val originalVariants = (0 until original.variantsLength).map { original.variants(it)!! }
            val originalBase = originalVariants.single { it.isBaseVariant }.diagLayer!!
            val originalTimeout =
                (0 until originalBase.comParamRefsLength)
                    .map { originalBase.comParamRefs(it)!! }
                    .single { it.comParam!!.shortName == "CP_Timeout" }

            assertEquals("SynthECU", originalBase.shortName)
            assertEquals("3000", originalTimeout.simpleValue!!.value)
            assertEquals(1, original.functionalGroupsLength)
            assertEquals("SharedDiagnostics", original.functionalGroups(0)!!.diagLayer!!.shortName)

            val resolvedOutput = File(tempDirectory, "resolved.mdd")
            converter.convert(
                input,
                resolvedOutput,
                ConverterOptions(resolveLogicalLinkComparams = true, skipSigning = true),
                mutableListOf(),
            )
            val resolved = readMddEcuData(resolvedOutput)
            val baseVariants =
                (0 until resolved.variantsLength)
                    .map { resolved.variants(it)!! }
                    .filter { it.isBaseVariant }
            val groups = (0 until resolved.functionalGroupsLength).map { resolved.functionalGroups(it)!! }

            assertEquals(listOf("SynthECU", "SynthECU__Rear"), baseVariants.map { it.diagLayer!!.shortName })
            assertEquals(listOf("SharedDiagnostics", "SharedDiagnostics__Body"), groups.map { it.diagLayer!!.shortName })
            assertEquals(
                listOf("500", "700", "900", "1100"),
                (baseVariants.map { it.diagLayer!! } + groups.map { it.diagLayer!! }).map { layer ->
                    (0 until layer.comParamRefsLength)
                        .map { layer.comParamRefs(it)!! }
                        .single { it.comParam!!.shortName == "CP_Timeout" }
                        .simpleValue!!
                        .value
                },
            )
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    private fun syntheticWithVis(): Map<String, String> {
        val fixtureDirectory = File(javaClass.getResource("/synthetic-odx")!!.toURI())
        val files =
            fixtureDirectory
                .listFiles()!!
                .filter { it.isFile }
                .sortedBy { it.name }
                .associateTo(linkedMapOf()) { it.name to it.readText() }
        val links =
            listOf(
                logicalLink("Front", overrides = simpleOverride("cp_timeout", "500")),
                logicalLink("Rear", overrides = simpleOverride("cp_timeout", "700")),
                logicalLink("Powertrain", target = "FUNCTIONAL-GROUP", overrides = simpleOverride("cp_timeout", "900")),
                logicalLink("Body", target = "FUNCTIONAL-GROUP", overrides = simpleOverride("cp_timeout", "1100")),
            ).joinToString("\n")
                .replace("bv_ecu", "bv_synth_ecu")
                .replace("fg_uds", "fg_shared_diag")
                .replace("protocol_can", "proto_uds")
                .replace("DOCREF=\"Diagnostics\"", "DOCREF=\"BaseContainer\"")
                .replace("ID-REF=\"proto_uds\" DOCREF=\"BaseContainer\"", "ID-REF=\"proto_uds\" DOCREF=\"ProtocolContainer\"")
                .replace("ID-REF=\"fg_shared_diag\" DOCREF=\"BaseContainer\"", "ID-REF=\"fg_shared_diag\" DOCREF=\"FuncGroupContainer\"")
                .replace("DOCREF=\"Parameters\"", "DOCREF=\"CANParameters\"")
                .replace("CAN_Stack", "UDS_CAN_Stack")
        files["vehicle.odx-v"] = vehicle(links, "")
        return files
    }

    private fun group(
        vararg logicalLinks: String,
        physicalOverrides: String = "",
        extraVariants: String = "",
        options: ConverterOptions = ConverterOptions(),
    ): ODXCollectionGroup {
        val files =
            linkedMapOf(
                "diagnostics.odx-d" to diagnostics(extraVariants),
                "parameters.odx-cs" to parameters,
            )
        if (logicalLinks.isNotEmpty()) {
            files["vehicle.odx-v"] = vehicle(logicalLinks.joinToString("\n"), physicalOverrides)
        }
        return parseGroup(files, options)
    }

    private fun parseGroup(
        files: Map<String, String>,
        options: ConverterOptions = ConverterOptions(),
    ): ODXCollectionGroup {
        val collector = ODXLinkCollector()
        val unmarshaller = context.createUnmarshaller().apply { listener = collector }
        val documents =
            (files + ("test-stacks.odx-c" to stacks)).mapValues { (filename, xml) ->
                collector.currentFile = filename
                unmarshaller.unmarshal(StreamSource(StringReader(xml)), ODX::class.java).value
            }
        return ODXCollectionGroup(documents, 0, options, Logger.getLogger("logical-link-test"), collector.linkToFile)
    }

    private fun logicalLink(
        name: String,
        target: String = "BASE-VARIANT",
        overrides: String = "",
    ): String {
        val targetId = if (target == "BASE-VARIANT") "bv_ecu" else "fg_uds"
        return """
            <LOGICAL-LINK xsi:type="MEMBER-LOGICAL-LINK" ID="link_$name">
              <SHORT-NAME>$name</SHORT-NAME>
              <PHYSICAL-VEHICLE-LINK-REF ID-REF="physical_can"/>
              <PROTOCOL-REF ID-REF="protocol_can" DOCREF="Diagnostics" DOCTYPE="LAYER"/>
              <$target-REF ID-REF="$targetId" DOCREF="Diagnostics" DOCTYPE="LAYER"/>
              <LINK-COMPARAM-REFS>$overrides</LINK-COMPARAM-REFS>
              <PROT-STACK-SNREF SHORT-NAME="CAN_Stack"/>
            </LOGICAL-LINK>
            """.trimIndent()
    }

    private fun simpleOverride(
        id: String,
        value: String,
        description: String = "",
    ): String =
        """
        <LINK-COMPARAM-REF ID-REF="$id" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET">
          <SIMPLE-VALUE>$value</SIMPLE-VALUE>
          ${if (description.isEmpty()) "" else "<DESC><p>$description</p></DESC>"}
        </LINK-COMPARAM-REF>
        """.trimIndent()

    private fun diagnostics(extraVariants: String): String =
        """
        <ODX MODEL-VERSION="2.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <DIAG-LAYER-CONTAINER ID="diagnostics"><SHORT-NAME>Diagnostics</SHORT-NAME>
            <PROTOCOLS><PROTOCOL ID="protocol_can"><SHORT-NAME>UDS_on_CAN</SHORT-NAME>
              <COMPARAM-REFS>
                <COMPARAM-REF ID-REF="cp_timeout" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET"><SIMPLE-VALUE>10</SIMPLE-VALUE><PROTOCOL-SNREF SHORT-NAME="UDS_on_CAN"/><PROT-STACK-SNREF SHORT-NAME="CAN_Stack"/></COMPARAM-REF>
                <COMPARAM-REF ID-REF="cp_retry" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET"><SIMPLE-VALUE>3</SIMPLE-VALUE><PROTOCOL-SNREF SHORT-NAME="UDS_on_CAN"/><PROT-STACK-SNREF SHORT-NAME="CAN_Stack"/></COMPARAM-REF>
              </COMPARAM-REFS>
            </PROTOCOL></PROTOCOLS>
            <FUNCTIONAL-GROUPS><FUNCTIONAL-GROUP ID="fg_uds"><SHORT-NAME>UDS_Functions</SHORT-NAME>
              <DIAG-COMMS><DIAG-SERVICE ID="service_tester"><SHORT-NAME>TesterPresent</SHORT-NAME></DIAG-SERVICE></DIAG-COMMS>
              <COMPARAM-REFS><COMPARAM-REF ID-REF="cp_timeout" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET"><SIMPLE-VALUE>25</SIMPLE-VALUE><PROTOCOL-SNREF SHORT-NAME="UDS_on_CAN"/><PROT-STACK-SNREF SHORT-NAME="CAN_Stack"/></COMPARAM-REF></COMPARAM-REFS>
              <PARENT-REFS><PARENT-REF xsi:type="PROTOCOL-REF" ID-REF="protocol_can" DOCREF="Diagnostics" DOCTYPE="LAYER"/></PARENT-REFS>
            </FUNCTIONAL-GROUP></FUNCTIONAL-GROUPS>
            <BASE-VARIANTS><BASE-VARIANT ID="bv_ecu"><SHORT-NAME>ECU</SHORT-NAME>
              <DIAG-COMMS><DIAG-SERVICE ID="service_read"><SHORT-NAME>ReadStatus</SHORT-NAME></DIAG-SERVICE></DIAG-COMMS>
              <COMPARAM-REFS><COMPARAM-REF ID-REF="cp_timeout" DOCREF="Parameters" DOCTYPE="COMPARAM-SUBSET"><SIMPLE-VALUE>20</SIMPLE-VALUE><PROTOCOL-SNREF SHORT-NAME="UDS_on_CAN"/><PROT-STACK-SNREF SHORT-NAME="CAN_Stack"/></COMPARAM-REF></COMPARAM-REFS>
              <PARENT-REFS><PARENT-REF xsi:type="PROTOCOL-REF" ID-REF="protocol_can" DOCREF="Diagnostics" DOCTYPE="LAYER"/></PARENT-REFS>
            </BASE-VARIANT>$extraVariants</BASE-VARIANTS>
          </DIAG-LAYER-CONTAINER>
        </ODX>
        """.trimIndent()

    private fun vehicle(
        links: String,
        physicalOverrides: String,
    ): String =
        """
        <ODX MODEL-VERSION="2.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <VEHICLE-INFO-SPEC ID="vehicle_spec"><SHORT-NAME>VehicleSpec</SHORT-NAME>
            <VEHICLE-INFORMATIONS><VEHICLE-INFORMATION><SHORT-NAME>Vehicle</SHORT-NAME>
              <LOGICAL-LINKS>$links</LOGICAL-LINKS>
              <PHYSICAL-VEHICLE-LINKS><PHYSICAL-VEHICLE-LINK ID="physical_can"><SHORT-NAME>CAN</SHORT-NAME>
                <LINK-COMPARAM-REFS>$physicalOverrides</LINK-COMPARAM-REFS>
              </PHYSICAL-VEHICLE-LINK></PHYSICAL-VEHICLE-LINKS>
            </VEHICLE-INFORMATION></VEHICLE-INFORMATIONS>
          </VEHICLE-INFO-SPEC>
        </ODX>
        """.trimIndent()

    private val parameters =
        """
        <ODX MODEL-VERSION="2.2.0">
          <COMPARAM-SUBSET ID="parameters" CATEGORY="BUSTYPE"><SHORT-NAME>Parameters</SHORT-NAME>
            <COMPARAMS>
              <COMPARAM ID="cp_timeout" PARAM-CLASS="TIMING" CPTYPE="STANDARD" CPUSAGE="ECU-COMM"><SHORT-NAME>CP_Timeout</SHORT-NAME><PHYSICAL-DEFAULT-VALUE>10</PHYSICAL-DEFAULT-VALUE></COMPARAM>
              <COMPARAM ID="cp_retry" PARAM-CLASS="TIMING" CPTYPE="STANDARD" CPUSAGE="ECU-COMM"><SHORT-NAME>CP_Retry</SHORT-NAME><PHYSICAL-DEFAULT-VALUE>3</PHYSICAL-DEFAULT-VALUE></COMPARAM>
            </COMPARAMS>
            <COMPLEX-COMPARAMS><COMPLEX-COMPARAM ID="cp_unique" PARAM-CLASS="UNIQUE-ID" CPTYPE="STANDARD" CPUSAGE="ECU-COMM" ALLOW-MULTIPLE-VALUES="true"><SHORT-NAME>CP_UniqueRespIdTable</SHORT-NAME></COMPLEX-COMPARAM></COMPLEX-COMPARAMS>
          </COMPARAM-SUBSET>
        </ODX>
        """.trimIndent()

    private val stacks =
        """
        <ODX MODEL-VERSION="2.2.0">
          <COMPARAM-SPEC ID="test_stacks"><SHORT-NAME>TestStacks</SHORT-NAME>
            <PROT-STACKS>
              <PROT-STACK ID="stack_can"><SHORT-NAME>CAN_Stack</SHORT-NAME></PROT-STACK>
              <PROT-STACK ID="stack_doip"><SHORT-NAME>DoIP_Stack</SHORT-NAME></PROT-STACK>
            </PROT-STACKS>
          </COMPARAM-SPEC>
        </ODX>
        """.trimIndent()

    companion object {
        private val context: JAXBContext =
            org.eclipse.persistence.jaxb.JAXBContextFactory
                .createContext(arrayOf(ODX::class.java), null)
    }
}
