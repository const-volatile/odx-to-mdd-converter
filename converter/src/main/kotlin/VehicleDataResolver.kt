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

import schema.odx.BASECOMPARAM
import schema.odx.BASEVARIANT
import schema.odx.BASEVARIANTREF
import schema.odx.COMPARAM
import schema.odx.COMPLEXCOMPARAM
import schema.odx.COMPLEXVALUE
import schema.odx.DIAGLAYER
import schema.odx.ECUSHAREDDATAREF
import schema.odx.ECUVARIANT
import schema.odx.FUNCTIONALGROUP
import schema.odx.FUNCTIONALGROUPREF
import schema.odx.GATEWAYLOGICALLINK
import schema.odx.HIERARCHYELEMENT
import schema.odx.LINKCOMPARAMREF
import schema.odx.LOGICALLINK
import schema.odx.ODXLINK
import schema.odx.PARENTREF
import schema.odx.PROTOCOL
import schema.odx.PROTOCOLREF
import schema.odx.SIMPLEVALUE
import schema.odx.VEHICLEINFORMATION
import java.util.Collections
import java.util.IdentityHashMap

internal data class VehicleDataSource(
    val name: String,
    val odx: ODXCollectionGroup,
) {
    val hasVehicleInformation: Boolean
        get() =
            odx.data.values.any {
                it.vehicleinfospec
                    ?.vehicleinformations
                    ?.vehicleinformation
                    ?.isNotEmpty() == true
            }
}

internal data class VehicleInformationData(
    val sourceShortName: String,
    val shortName: String,
    val logicalLinks: List<LogicalLinkData>,
)

internal data class LogicalLinkData(
    val id: String,
    val shortName: String,
    val targets: List<DiagnosticLayerTarget>,
    val protocol: String?,
    val protocolStack: String?,
    val physicalLinkId: String?,
    val physicalLinkType: String?,
    val gatewayLinks: List<LogicalLinkKey>,
    val comParams: List<VehicleComParamData>,
    val physicalLinkSourceShortName: String?,
    val isGateway: Boolean,
    val gatewaySemantic: String?,
)

internal data class DiagnosticLayerTarget(
    val databaseId: String,
    val kind: TargetKind,
    val shortName: String,
)

internal enum class TargetKind {
    BASE_VARIANT,
    FUNCTIONAL_GROUP,
}

internal data class LogicalLinkKey(
    val sourceShortName: String,
    val id: String,
)

internal data class VehicleComParamData(
    val shortName: String,
    val definitionDocument: String,
    val definitionId: String,
    val value: VehicleParamValue?,
)

internal data class VehicleParamValue(
    val shortName: String?,
    val simpleValue: String?,
    val complexValues: List<VehicleParamValue>?,
)

internal fun resolveVehicleInformation(sources: List<VehicleDataSource>): Map<VehicleDataSource, List<VehicleInformationData>> =
    VehicleDataResolver(sources).resolve()

private class VehicleDataResolver(
    sources: List<VehicleDataSource>,
) {
    private class Document(
        val source: VehicleDataSource,
        val fileName: String,
        val collection: ODXCollection,
    ) {
        val name get() = collection.containerKey
        val vehicles get() =
            collection.odx.vehicleinfospec
                ?.vehicleinformations
                ?.vehicleinformation
                .orEmpty()
        val layers: List<DIAGLAYER> by lazy {
            collection.basevariants.values + collection.ecuvariants.values + collection.functionalGroups.values +
                collection.protocols.values + collection.ecuSharedDatas.values
        }
        val comParams: Map<String, BASECOMPARAM> by lazy {
            fun definitions(definition: BASECOMPARAM): Sequence<BASECOMPARAM> =
                sequence {
                    yield(definition)
                    if (definition is COMPLEXCOMPARAM) {
                        definition.comparamOrCOMPLEXCOMPARAM.forEach { yieldAll(definitions(it)) }
                    }
                }
            (collection.comparams.values.asSequence() + collection.complexComparams.values.asSequence())
                .flatMap(::definitions)
                .associateBy { it.id }
        }

        override fun toString(): String = "${source.name}/$fileName"
    }

    private data class Located<out T>(
        val document: Document,
        val value: T,
    )

    private data class Param(
        val definition: Located<BASECOMPARAM>,
        val simpleValue: SIMPLEVALUE?,
        val complexValue: COMPLEXVALUE?,
    )

    private val documents =
        sources.flatMap { source ->
            source.odx.data.map { (file, odx) -> Document(source, file, ODXCollection(odx)) }
        }
    private val scopes by lazy {
        documents
            .flatMap { document ->
                (listOf(document.name) + document.layers.map { it.shortname }).distinct().map { it to document }
            }.groupBy({ it.first }, { it.second })
    }
    private val databases by lazy {
        sources
            .filter { it.odx.basevariants.isNotEmpty() || it.odx.functionalGroups.isNotEmpty() }
            .groupBy { it.odx.ecuName }
    }

    fun resolve(): Map<VehicleDataSource, List<VehicleInformationData>> {
        val vehicleDocuments = documents.filter { it.vehicles.isNotEmpty() }
        vehicleDocuments.groupBy { it.name }.forEach { (name, group) ->
            if (group.size > 1) {
                throw OdxResolutionException("Ambiguous VEHICLE-INFO-SPEC '$name' in ${group.joinToString()}")
            }
        }
        return vehicleDocuments.groupBy { it.source }.mapValues { (_, documents) ->
            documents.flatMap(::resolveDocument)
        }
    }

    private fun resolveDocument(document: Document): List<VehicleInformationData> {
        val duplicateIds =
            document.vehicles
                .flatMap { it.logicallinks?.logicallink.orEmpty() }
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
        if (duplicateIds.isNotEmpty()) {
            throw OdxResolutionException("Duplicate LOGICAL-LINK IDs in $document: ${duplicateIds.keys.joinToString()}")
        }
        return document.vehicles.map { vehicle ->
            VehicleInformationData(
                document.name,
                vehicle.shortname,
                vehicle.logicallinks
                    ?.logicallink
                    .orEmpty()
                    .map { link -> LinkResolver(document, vehicle, link).resolve() },
            )
        }
    }

    private inner class LinkResolver(
        private val origin: Document,
        private val vehicle: VEHICLEINFORMATION,
        private val link: LOGICALLINK,
    ) {
        fun resolve(): LogicalLinkData {
            val bv = link.basevariantref?.let { ref -> find(origin, ref, "BASE-VARIANT") { it.collection.basevariants[ref.idref] } }
            val fg =
                link.functionalgroupref?.let { ref ->
                    find(origin, ref, "FUNCTIONAL-GROUP") { it.collection.functionalGroups[ref.idref] }
                }
            val targets =
                listOfNotNull(bv?.let { target(it, TargetKind.BASE_VARIANT) }, fg?.let { target(it, TargetKind.FUNCTIONAL_GROUP) })
            val gatewayLinks =
                link.gatewaylogicallinkrefs?.gatewaylogicallinkref.orEmpty().map { ref ->
                    val gateway =
                        find(origin, ref, "GATEWAY-LOGICAL-LINK") { document ->
                            document.vehicles.flatMap { it.logicallinks?.logicallink.orEmpty() }.find { it.id == ref.idref }
                        }
                    if (gateway.value !is GATEWAYLOGICALLINK) {
                        fail("GATEWAY-LOGICAL-LINK-REF '${ref.idref}' points to a member logical link in ${gateway.document}")
                    }
                    LogicalLinkKey(gateway.document.name, gateway.value.id)
                }
            if (targets.isEmpty() &&
                gatewayLinks.isEmpty() &&
                link.ecuproxyrefs
                    ?.ecuproxyref
                    .orEmpty()
                    .isNotEmpty()
            ) {
                fail("ECU-PROXY-only links cannot be bound to a base variant or functional group")
            }

            val physical =
                link.physicalvehiclelinkref?.let { ref ->
                    find(origin, ref, "PHYSICAL-VEHICLE-LINK") { document ->
                        document.vehicles.flatMap { it.physicalvehiclelinks?.physicalvehiclelink.orEmpty() }.find { it.id == ref.idref }
                    }
                }
            val layers = listOfNotNull(fg, bv)
            val protocol =
                link.protocolref?.let { ref ->
                    find(origin, ref, "PROTOCOL", allowCopies = true) { it.collection.protocols[ref.idref] }
                } ?: inferProtocol(layers)
            val allowedProtocols =
                protocol?.let {
                    inheritedLayers(listOf(it))
                        .filter { layer -> layer.value is PROTOCOL }
                        .map { layer -> layer.document.name to layer.value.id }
                        .toSet()
                }
            val inherited = inheritedLayers(listOfNotNull(protocol) + layers, allowedProtocols)
            val protocolName = protocol?.value?.shortname
            val stack =
                link.protstacksnref?.shortname ?: protocol?.value?.protstacksnref?.shortname
                    ?: inferStack(inherited, protocolName)
            if (stack != null) validateStack(stack, protocol)

            val params = linkedMapOf<Pair<String, String>, Param>()
            for ((document, layer) in inherited) {
                for (ref in layer.comparamrefs?.comparamref.orEmpty()) {
                    if (ref.protocolsnref?.shortname?.let { it != protocolName } == true) continue
                    if (ref.protstacksnref?.shortname?.let { it != stack } == true) continue
                    val definition = comParam(document, ref.idref, ref.docref)
                    addParam(params, Param(definition, ref.simplevalue, ref.complexvalue))
                }
            }
            physical?.let { (document, physicalLink) ->
                applyOverrides(params, document, physicalLink.linkcomparamrefs?.linkcomparamref.orEmpty())
            }
            applyOverrides(params, origin, link.linkcomparamrefs?.linkcomparamref.orEmpty())

            return LogicalLinkData(
                link.id,
                link.shortname,
                targets,
                protocolName,
                stack,
                physical?.value?.id,
                physical?.value?.type,
                gatewayLinks,
                params.values.map(::paramData),
                physical?.document?.name,
                link is GATEWAYLOGICALLINK,
                (link as? GATEWAYLOGICALLINK)?.semantic,
            )
        }

        private fun target(
            layer: Located<HIERARCHYELEMENT>,
            kind: TargetKind,
        ): DiagnosticLayerTarget {
            val group = layer.document.source.odx
            val candidates =
                when (kind) {
                    TargetKind.BASE_VARIANT -> group.basevariants
                    TargetKind.FUNCTIONAL_GROUP -> group.functionalGroups
                }.filter { it.shortname == layer.value.shortname }
            if (candidates.singleOrNull() !== layer.value) {
                fail(
                    "${kind.name} '${layer.value.shortname}' is missing or ambiguous in ${layer.document.source.name}'s diagnostic payload",
                )
            }
            val databaseId = group.ecuName
            if (databases[databaseId].orEmpty().size != 1) {
                fail("Ambiguous MDD database ID '$databaseId' in ${databases[databaseId].orEmpty().joinToString { it.name }}")
            }
            return DiagnosticLayerTarget(databaseId, kind, layer.value.shortname)
        }

        private fun inferProtocol(layers: List<Located<HIERARCHYELEMENT>>): Located<PROTOCOL>? {
            val inherited = inheritedLayers(layers)
            val protocols =
                inherited.mapNotNull { layer ->
                    (layer.value as? PROTOCOL)?.let { Located(layer.document, it) }
                }
            val protocolParents =
                protocols
                    .flatMap(::parents)
                    .filter { it.value is PROTOCOL }
                    .map { it.document.name to it.value.id }
                    .toSet()
            val names =
                protocols.filter { (it.document.name to it.value.id) !in protocolParents }.map { it.value.shortname } +
                    inherited.filterNot { it.value is PROTOCOL }.flatMap { layer ->
                        layer.value.comparamrefs
                            ?.comparamref
                            .orEmpty()
                            .mapNotNull { it.protocolsnref?.shortname }
                    }
            val explicitStack = link.protstacksnref?.shortname
            val candidates =
                names.distinct().map { name ->
                    val matches =
                        protocols.filter { it.value.shortname == name }.ifEmpty {
                            documents.flatMap { document ->
                                document.collection.protocols.values
                                    .filter { it.shortname == name }
                                    .map { Located(document, it) }
                            }
                        }
                    val preferred = layers.firstOrNull()?.document ?: origin
                    select(matches, preferred, "PROTOCOL '$name'", allowCopies = true)
                }
            val matchingStack = candidates.filter { it.value.protstacksnref?.shortname == explicitStack }
            val applicable = if (explicitStack != null && matchingStack.isNotEmpty()) matchingStack else candidates
            if (applicable.size > 1) {
                fail("Multiple protocols are available (${applicable.joinToString { it.value.shortname }}); a PROTOCOL-REF is required")
            }
            return applicable.singleOrNull()
        }

        private fun inferStack(
            layers: List<Located<HIERARCHYELEMENT>>,
            protocolName: String?,
        ): String? {
            val stacks =
                layers
                    .flatMap { layer ->
                        layer.value.comparamrefs
                            ?.comparamref
                            .orEmpty()
                            .filter { it.protocolsnref?.shortname?.let { name -> name == protocolName } != false }
                            .mapNotNull { it.protstacksnref?.shortname }
                    }.distinct()
            if (stacks.size > 1) {
                fail("Multiple protocol stacks are available (${stacks.joinToString()}); a PROT-STACK-SNREF is required")
            }
            return stacks.singleOrNull()
        }

        private fun validateStack(
            stack: String,
            protocol: Located<PROTOCOL>?,
        ) {
            val spec =
                protocol?.value?.comparamspecref?.let { ref ->
                    find(protocol.document, ref, "COMPARAM-SPEC", allowCopies = true) { it.collection.comparamSpecs[ref.idref] }
                }
            val documentsToSearch = spec?.let { listOf(it.document) } ?: documents
            val matches =
                documentsToSearch.flatMap { document ->
                    document.collection.protStacks.values
                        .filter { it.shortname == stack }
                        .map { Located(document, it) }
                }
            select(matches, protocol?.document ?: origin, "PROT-STACK '$stack'", allowCopies = true)
        }

        private fun inheritedLayers(
            roots: List<Located<HIERARCHYELEMENT>>,
            allowedProtocols: Set<Pair<String, String>>? = null,
        ): List<Located<HIERARCHYELEMENT>> {
            val active = Collections.newSetFromMap(IdentityHashMap<HIERARCHYELEMENT, Boolean>())
            val visited = Collections.newSetFromMap(IdentityHashMap<HIERARCHYELEMENT, Boolean>())
            val layers = mutableListOf<Located<HIERARCHYELEMENT>>()

            fun visit(layer: Located<HIERARCHYELEMENT>) {
                if (layer.value is PROTOCOL &&
                    allowedProtocols != null &&
                    (layer.document.name to layer.value.id) !in allowedProtocols
                ) {
                    return
                }
                if (layer.value in active) fail("Cyclic diagnostic-layer inheritance at '${layer.value.shortname}'")
                if (!visited.add(layer.value)) return
                active.add(layer.value)
                parents(layer).sortedBy { parent -> priority(parent.value) }.forEach(::visit)
                active.remove(layer.value)
                layers.add(layer)
            }
            roots.forEach(::visit)
            return layers
        }

        private fun parents(layer: Located<HIERARCHYELEMENT>): List<Located<HIERARCHYELEMENT>> =
            parentRefs(layer.value).mapNotNull { ref ->
                val parent =
                    find(layer.document, ref.idref, ref.docref, "PARENT-REF", allowCopies = ref is PROTOCOLREF) { document ->
                        val col = document.collection
                        when (ref) {
                            is BASEVARIANTREF -> col.basevariants[ref.idref]
                            is FUNCTIONALGROUPREF -> col.functionalGroups[ref.idref]
                            is PROTOCOLREF -> col.protocols[ref.idref]
                            is ECUSHAREDDATAREF -> col.ecuSharedDatas[ref.idref]
                            else ->
                                col.basevariants[ref.idref] ?: col.ecuvariants[ref.idref] ?: col.protocols[ref.idref]
                                    ?: col.functionalGroups[ref.idref] ?: col.tables[ref.idref] ?: col.ecuSharedDatas[ref.idref]
                        }
                    }
                (parent.value as? HIERARCHYELEMENT)?.let { Located(parent.document, it) }
            }

        private fun applyOverrides(
            params: MutableMap<Pair<String, String>, Param>,
            document: Document,
            refs: List<LINKCOMPARAMREF>,
        ) {
            for (ref in refs) {
                addParam(params, Param(comParam(document, ref.idref, ref.docref), ref.simplevalue, ref.complexvalue))
            }
        }

        private fun comParam(
            document: Document,
            id: String,
            docref: String?,
        ): Located<BASECOMPARAM> = find(document, id, docref, "COMPARAM", allowCopies = true) { it.comParams[id] }

        private fun addParam(
            params: MutableMap<Pair<String, String>, Param>,
            param: Param,
        ) {
            val key = param.definition.document.name to param.definition.value.id
            val previous = params[key]
            if (previous != null && previous.definition.value != param.definition.value) {
                fail("Conflicting COMPARAM definitions '${key.second}' in ${previous.definition.document} and ${param.definition.document}")
            }
            params[key] = param
        }

        private fun paramData(param: Param): VehicleComParamData {
            val definition = param.definition.value
            val value =
                when {
                    param.simpleValue != null && param.complexValue != null ->
                        fail(
                            "COMPARAM '${definition.shortname}' has both value types",
                        )
                    param.simpleValue != null -> VehicleParamValue(definition.shortname, param.simpleValue.value.orEmpty(), null)
                    param.complexValue != null -> complexValue(param.complexValue, definition as? COMPLEXCOMPARAM, definition.shortname)
                    definition is COMPARAM ->
                        definition.physicaldefaultvalue?.let { VehicleParamValue(definition.shortname, it, null) }
                    definition is COMPLEXCOMPARAM -> complexDefault(definition)
                    else -> fail("Unknown COMPARAM type for '${definition.shortname}'")
                }
            return VehicleComParamData(definition.shortname, param.definition.document.name, definition.id, value)
        }

        private fun complexDefault(definition: COMPLEXCOMPARAM): VehicleParamValue? {
            val values =
                definition.complexphysicaldefaultvalue
                    ?.complexvalues
                    ?.complexvalue
                    .orEmpty()
            return when (values.size) {
                0 -> null
                1 -> complexValue(values.single(), definition, definition.shortname)
                else -> VehicleParamValue(definition.shortname, null, values.map { complexValue(it, definition, null) })
            }
        }

        private fun complexValue(
            value: COMPLEXVALUE,
            definition: COMPLEXCOMPARAM?,
            name: String?,
        ): VehicleParamValue {
            val entries = value.simplevalueOrCOMPLEXVALUE
            val members = definition?.comparamOrCOMPLEXCOMPARAM.orEmpty()
            // A multiple-value parameter can carry a list of rows, each with the same member definitions.
            val rows =
                definition?.isALLOWMULTIPLEVALUES == true &&
                    entries.isNotEmpty() &&
                    entries.all { it is COMPLEXVALUE } &&
                    (entries.size != members.size || members.any { it is COMPARAM })
            val children =
                entries.mapIndexed { index, entry ->
                    val member = members.getOrNull(index)
                    when (entry) {
                        is SIMPLEVALUE -> VehicleParamValue((member as? COMPARAM)?.shortname, entry.value.orEmpty(), null)
                        is COMPLEXVALUE -> {
                            val nested = if (rows) definition else member as? COMPLEXCOMPARAM
                            complexValue(entry, nested, if (rows) null else nested?.shortname)
                        }
                        else -> fail("Unexpected complex value entry in COMPARAM '$name'")
                    }
                }
            return VehicleParamValue(name, null, children)
        }

        private fun <T : Any> find(
            from: Document,
            ref: ODXLINK,
            expected: String,
            allowCopies: Boolean = false,
            lookup: (Document) -> T?,
        ): Located<T> = find(from, ref.idref, ref.docref, expected, allowCopies, lookup)

        private fun <T : Any> find(
            from: Document,
            id: String,
            docref: String?,
            expected: String,
            allowCopies: Boolean = false,
            lookup: (Document) -> T?,
        ): Located<T> {
            val candidates = if (docref == null) listOf(from) else scopes[docref].orEmpty()
            val matches = candidates.mapNotNull { document -> lookup(document)?.let { Located(document, it) } }
            return select(matches, from, "$expected '$id' in '${docref ?: from.name}'", allowCopies)
        }

        private fun <T : Any> select(
            matches: List<Located<T>>,
            from: Document,
            expected: String,
            allowCopies: Boolean,
        ): Located<T> {
            val preferred = matches.filter { it.document.source === from.source }.ifEmpty { matches }
            val first = preferred.firstOrNull() ?: fail("Unresolved $expected")
            if (preferred.size > 1 && !(allowCopies && preferred.all { it.value == first.value })) {
                fail("Ambiguous $expected in ${preferred.joinToString { it.document.toString() }}")
            }
            return first
        }

        private fun fail(message: String): Nothing =
            throw OdxResolutionException("$origin / ${vehicle.shortname} / ${link.shortname} (${link.id}): $message")
    }
}

private fun parentRefs(layer: HIERARCHYELEMENT): List<PARENTREF> =
    when (layer) {
        is BASEVARIANT -> layer.parentrefs?.parentref.orEmpty()
        is FUNCTIONALGROUP -> layer.parentrefs?.parentref.orEmpty()
        is PROTOCOL -> layer.parentrefs?.parentref.orEmpty()
        is ECUVARIANT -> layer.parentrefs?.parentref.orEmpty()
        else -> emptyList()
    }

private fun priority(layer: HIERARCHYELEMENT): Int =
    when (layer) {
        is PROTOCOL -> 0
        is FUNCTIONALGROUP -> 1
        is BASEVARIANT -> 2
        else -> 3
    }
