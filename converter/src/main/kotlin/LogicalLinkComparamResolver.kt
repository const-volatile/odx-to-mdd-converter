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

import schema.odx.BASEVARIANT
import schema.odx.BASEVARIANTS
import schema.odx.COMPARAMREF
import schema.odx.COMPARAMREFS
import schema.odx.COMPLEXVALUE
import schema.odx.DIAGLAYER
import schema.odx.ECUVARIANT
import schema.odx.FUNCTIONALGROUP
import schema.odx.FUNCTIONALGROUPS
import schema.odx.HIERARCHYELEMENT
import schema.odx.LINKCOMPARAMREF
import schema.odx.LOGICALLINK
import schema.odx.PARENTREF
import schema.odx.PROTOCOL
import schema.odx.SIMPLEVALUE
import schema.odx.SNREF
import schema.odx.VEHICLEINFORMATION
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.logging.Logger

/**
 * Deliberately simple, opt-in projection of ODX-V communication contexts onto
 * ordinary BV/FG records. Diagnostic children stay shared, so their source-file
 * ownership and references remain valid. Incoming layer references are not
 * expanded into additional ECU-variant or functional-group membership lineages.
 */
internal class LogicalLinkComparamResolver(
    private val source: ODXCollectionGroup,
    private val logger: Logger,
    ownership: IdentityHashMap<Any, String>,
) {
    private val outputOwnership = IdentityHashMap(ownership)
    private val owners = IdentityHashMap<DIAGLAYER, String>()
    private val definitionContainers = IdentityHashMap<Any, String>()
    private val profiles = IdentityHashMap<HIERARCHYELEMENT, MutableList<Profile>>()
    private val usedNames = mutableSetOf<String>()
    private val usedIds = mutableSetOf<String>()

    private data class ParamKey(
        val container: String?,
        val id: String,
        val protocol: String?,
        val stack: String?,
    )

    private data class ParamValue(
        val simple: String?,
        val complex: List<Any?>?,
    )

    private data class Profile(
        val layer: HIERARCHYELEMENT,
        val values: Map<ParamKey, ParamValue>,
    )

    fun resolve(): ODXCollectionGroup {
        source.data.forEach { (filename, odx) ->
            odx.diaglayercontainer?.let { dlc ->
                val layers =
                    dlc.basevariants?.basevariant.orEmpty() + dlc.ecuvariants?.ecuvariant.orEmpty() +
                        dlc.functionalgroups?.functionalgroup.orEmpty() + dlc.protocols?.protocol.orEmpty() +
                        dlc.ecushareddatas?.ecushareddata.orEmpty()
                layers.forEach { layer ->
                    owners[layer] = filename
                    usedNames.add(layer.shortname)
                }
            }
        }
        collectIds(source.data.values)
        source.collections.values.forEach { collection ->
            (collection.comparams.values + collection.complexComparams.values).forEach { definition ->
                definitionContainers[definition] = collection.containerKey
            }
        }

        source.data.forEach { (filename, odx) ->
            odx.vehicleinfospec?.vehicleinformations?.vehicleinformation.orEmpty().forEach { vehicle ->
                vehicle.logicallinks?.logicallink.orEmpty().forEach { link ->
                    try {
                        resolveLink(filename, vehicle, link)
                    } catch (error: OdxResolutionException) {
                        if (!source.options.lenient) throw error
                        logger.warning(error.message)
                    }
                }
            }
        }
        if (profiles.isEmpty()) return source

        val documents =
            source.data.mapValues { (_, odx) ->
                val dlc = odx.diaglayercontainer ?: return@mapValues odx
                val bvs = dlc.basevariants?.basevariant.orEmpty()
                val fgs = dlc.functionalgroups?.functionalgroup.orEmpty()
                if ((bvs + fgs).none { profiles.containsKey(it) }) return@mapValues odx
                shallowCopy(odx).apply {
                    diaglayercontainer =
                        shallowCopy(dlc).apply {
                            if (dlc.basevariants != null) {
                                basevariants =
                                    BASEVARIANTS().apply {
                                        bvs.forEach { original ->
                                            basevariant.addAll(profiles[original]?.map { it.layer as BASEVARIANT } ?: listOf(original))
                                        }
                                    }
                            }
                            if (dlc.functionalgroups != null) {
                                functionalgroups =
                                    FUNCTIONALGROUPS().apply {
                                        fgs.forEach { original ->
                                            functionalgroup.addAll(
                                                profiles[original]?.map { it.layer as FUNCTIONALGROUP } ?: listOf(original),
                                            )
                                        }
                                    }
                            }
                        }
                }
            }
        return ODXCollectionGroup(documents, source.rawSize, source.options, logger, outputOwnership)
    }

    private fun resolveLink(
        filename: String,
        vehicle: VEHICLEINFORMATION,
        link: LOGICALLINK,
    ) {
        val targets =
            listOfNotNull(
                link.basevariantref?.let { ref ->
                    source.resolveBaseVariant(ref)
                        ?: failure(link, "Unresolved BASE-VARIANT-REF '${ref.idref}'")
                },
                link.functionalgroupref?.let { ref ->
                    source.resolveFunctionalGroup(ref)
                        ?: failure(link, "Unresolved FUNCTIONAL-GROUP-REF '${ref.idref}'")
                },
            )
        if (targets.isEmpty()) return

        val physical =
            link.physicalvehiclelinkref?.let { ref ->
                val local =
                    vehicle.physicalvehiclelinks
                        ?.physicalvehiclelink
                        .orEmpty()
                        .firstOrNull { it.id == ref.idref }
                val document =
                    if (ref.docref == null) {
                        source.data[filename]
                    } else {
                        source.data.values.firstOrNull { it.vehicleinfospec?.shortname == ref.docref }
                    }
                val resolved =
                    document
                        ?.vehicleinfospec
                        ?.vehicleinformations
                        ?.vehicleinformation
                        .orEmpty()
                        .flatMap { it.physicalvehiclelinks?.physicalvehiclelink.orEmpty() }
                        .firstOrNull { it.id == ref.idref }
                (if (ref.docref == null) local ?: resolved else resolved)
                    ?: failure(link, "Unresolved PHYSICAL-VEHICLE-LINK-REF '${ref.idref}'")
            }
        val physicalFile =
            link.physicalvehiclelinkref?.docref?.let { docref ->
                source.data.entries
                    .firstOrNull { it.value.vehicleinfospec?.shortname == docref }
                    ?.key
            } ?: filename
        val overrides =
            physical
                ?.linkcomparamrefs
                ?.linkcomparamref
                .orEmpty()
                .map { it to physicalFile } +
                link.linkcomparamrefs
                    ?.linkcomparamref
                    .orEmpty()
                    .map { it to filename }

        // Finish all resolution for a link before publishing any of its profiles.
        val computed =
            targets.map { target ->
                val protocol =
                    link.protocolref?.let { ref ->
                        source.resolveProtocol(ref) ?: failure(link, "Unresolved PROTOCOL-REF '${ref.idref}'")
                    } ?: inferProtocol(target, link, overrides.isNotEmpty())
                val stack = link.protstacksnref?.shortname ?: protocol?.protstacksnref?.shortname
                if (stack != null && source.resolveProtStackByShortName(stack) == null) {
                    failure(link, "Unresolved PROT-STACK-SNREF '$stack'")
                }
                if (overrides.isNotEmpty() && protocol == null) {
                    failure(link, "Cannot determine a unique protocol for link communication parameters")
                }
                val refs = linkedMapOf<ParamKey, COMPARAMREF>()
                val active = Collections.newSetFromMap(IdentityHashMap<HIERARCHYELEMENT, Boolean>())
                inherit(target, protocol?.shortname, stack, refs, active, link)
                overrides.forEach { (override, file) ->
                    val ref = promote(override, file, protocol?.shortname, stack)
                    if (source.resolveComparam(ref) == null && source.resolveComplexComparam(ref) == null) {
                        failure(link, "Unresolved LINK-COMPARAM-REF '${override.idref}' in '${ref.docref}'")
                    }
                    refs[key(ref)] = ref
                }
                target to refs
            }
        computed.forEach { (target, refs) ->
            val values = refs.mapValues { (_, ref) -> ParamValue(ref.simplevalue?.value, ref.complexvalue?.let(::complexValue)) }
            val existing = profiles.getOrPut(target) { mutableListOf() }
            if (existing.any { it.values == values }) return@forEach
            val copy = shallowCopy(target)
            copy.comparamrefs = COMPARAMREFS().apply { comparamref.addAll(refs.values) }
            if (existing.isNotEmpty()) {
                val suffix =
                    link.shortname
                        .orEmpty()
                        .ifEmpty { link.id }
                        .replace(Regex("[^A-Za-z0-9_]"), "_")
                copy.shortname = unique("${target.shortname}__$suffix", usedNames)
                copy.id = unique("${target.id}__$suffix", usedIds)
                logger.info(
                    "Logical link '${link.shortname}' creates ${copy.javaClass.simpleName} '${copy.shortname}' with distinct communication parameters",
                )
            }
            existing.add(Profile(copy, values))
        }
    }

    private fun inherit(
        layer: HIERARCHYELEMENT,
        protocol: String?,
        stack: String?,
        refs: MutableMap<ParamKey, COMPARAMREF>,
        active: MutableSet<HIERARCHYELEMENT>,
        link: LOGICALLINK,
    ) {
        if (!active.add(layer)) failure(link, "Cyclic diagnostic-layer inheritance at '${layer.shortname}'")
        parents(layer)
            .map { ref -> source.resolveParent(ref) ?: failure(link, "Unresolved PARENT-REF '${ref.idref}'") }
            .filterIsInstance<HIERARCHYELEMENT>()
            .sortedBy { parent ->
                if (parent is PROTOCOL) {
                    0
                } else if (parent is FUNCTIONALGROUP) {
                    1
                } else if (parent is BASEVARIANT) {
                    2
                } else {
                    3
                }
            }.forEach { inherit(it, protocol, stack, refs, active, link) }
        layer.comparamrefs?.comparamref.orEmpty().forEach { original ->
            val ref = shallowCopy(original)
            val file = source.sourceFileFor(original) ?: owners[layer]
            if (ref.docref == null) ref.docref = file?.let { source.data[it] }?.let { ODXCollection(it).containerKey }
            val effectiveProtocol = ref.protocolsnref?.shortname ?: (layer as? PROTOCOL)?.shortname ?: protocol
            val effectiveStack =
                ref.protstacksnref?.shortname ?: (layer as? PROTOCOL)?.protstacksnref?.shortname
                    ?: stack?.takeIf { effectiveProtocol == protocol }
            ref.protocolsnref = effectiveProtocol?.let { name -> SNREF().apply { shortname = name } }
            ref.protstacksnref = effectiveStack?.let { name -> SNREF().apply { shortname = name } }
            if (file != null) outputOwnership[ref] = file
            refs[key(ref)] = ref
        }
        active.remove(layer)
    }

    private fun inferProtocol(
        target: HIERARCHYELEMENT,
        link: LOGICALLINK,
        required: Boolean,
    ): PROTOCOL? {
        val found = linkedSetOf<String>()
        val visited = Collections.newSetFromMap(IdentityHashMap<HIERARCHYELEMENT, Boolean>())

        fun visit(layer: HIERARCHYELEMENT) {
            if (!visited.add(layer)) return
            if (layer is PROTOCOL) found.add(layer.shortname)
            layer.comparamrefs
                ?.comparamref
                .orEmpty()
                .mapNotNullTo(found) { it.protocolsnref?.shortname }
            parents(layer).forEach { ref -> (source.resolveParent(ref) as? HIERARCHYELEMENT)?.let(::visit) }
        }
        visit(target)
        if (found.size > 1) {
            if (required) failure(link, "Multiple protocols are available; an explicit PROTOCOL-REF is required")
            return null
        }
        return found.singleOrNull()?.let { name -> source.resolveProtocolByShortName(name) ?: failure(link, "Unresolved protocol '$name'") }
    }

    private fun promote(
        original: LINKCOMPARAMREF,
        filename: String,
        protocol: String?,
        stack: String?,
    ): COMPARAMREF =
        COMPARAMREF().apply {
            idref = original.idref
            docref = original.docref ?: source.data[filename]?.let { ODXCollection(it).containerKey }
            doctype = original.doctype
            revision = original.revision
            simplevalue = original.simplevalue
            complexvalue = original.complexvalue
            desc = original.desc
            protocolsnref = protocol?.let { name -> SNREF().apply { shortname = name } }
            protstacksnref = stack?.let { name -> SNREF().apply { shortname = name } }
            outputOwnership[this] = filename
        }

    private fun key(ref: COMPARAMREF): ParamKey {
        // Canonicalize container-vs-layer DOCREF aliases by resolving the actual definition.
        val definition = source.resolveComparam(ref) ?: source.resolveComplexComparam(ref)
        val container = definition?.let { definitionContainers[it] } ?: ref.docref
        return ParamKey(container, ref.idref, ref.protocolsnref?.shortname, ref.protstacksnref?.shortname)
    }

    private fun parents(layer: HIERARCHYELEMENT): List<PARENTREF> =
        when (layer) {
            is BASEVARIANT -> layer.parentrefs?.parentref.orEmpty()
            is FUNCTIONALGROUP -> layer.parentrefs?.parentref.orEmpty()
            is PROTOCOL -> layer.parentrefs?.parentref.orEmpty()
            is ECUVARIANT -> layer.parentrefs?.parentref.orEmpty()
            else -> emptyList()
        }

    private fun complexValue(value: COMPLEXVALUE): List<Any?> =
        value.simplevalueOrCOMPLEXVALUE.map {
            when (it) {
                is SIMPLEVALUE -> it.value
                is COMPLEXVALUE -> complexValue(it)
                else -> error("Unexpected complex communication parameter value: ${it.javaClass.simpleName}")
            }
        }

    private fun failure(
        link: LOGICALLINK,
        message: String,
    ): Nothing = throw OdxResolutionException("Logical link '${link.shortname}' (${link.id}): $message")

    private fun unique(
        preferred: String,
        used: MutableSet<String>,
    ): String {
        var candidate = preferred
        var suffix = 2
        while (!used.add(candidate)) candidate = "${preferred}_${suffix++}"
        return candidate
    }

    private fun collectIds(roots: Collection<Any>) {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?) {
            if (value == null || !visited.add(value)) return
            if (value is Collection<*>) {
                value.forEach(::visit)
            } else if (value.javaClass.name.startsWith("schema.odx.") && !value.javaClass.isEnum) {
                fields(value.javaClass).forEach { field ->
                    val child = field.get(value)
                    if (field.name == "id" && child is String) usedIds.add(child)
                    visit(child)
                }
            }
        }
        roots.forEach(::visit)
    }

    /** Copy only JAXB fields; child objects and their tracked references stay shared. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> shallowCopy(original: T): T {
        val copy = original.javaClass.getDeclaredConstructor().newInstance() as T
        fields(original.javaClass).forEach { field -> field.set(copy, field.get(original)) }
        return copy
    }

    private fun fields(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence(type) { it.superclass }
            .takeWhile { it.name.startsWith("schema.odx.") }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .toList()
}
