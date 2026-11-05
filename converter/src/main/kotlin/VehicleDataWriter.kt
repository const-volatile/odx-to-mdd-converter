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

import com.google.flatbuffers.FlatBufferBuilder
import dataformat.vehicle.CommunicationParameter
import dataformat.vehicle.DiagnosticLayerKind
import dataformat.vehicle.DiagnosticLayerRef
import dataformat.vehicle.LogicalLink
import dataformat.vehicle.LogicalLinkRef
import dataformat.vehicle.ParameterValue
import dataformat.vehicle.VehicleData
import dataformat.vehicle.VehicleInformation

internal class VehicleDataWriter {
    private val builder = FlatBufferBuilder()
    private val targetOffsets = mutableMapOf<DiagnosticLayerTarget, Int>()

    fun write(vehicles: List<VehicleInformationData>): ByteArray {
        val entries = VehicleData.createVehicleInformationsVector(builder, vehicles.map(::writeVehicle).toIntArray())
        val root = VehicleData.createVehicleData(builder, builder.createString("1"), entries)
        VehicleData.finishVehicleDataBuffer(builder, root)
        return builder.sizedByteArray()
    }

    private fun writeVehicle(vehicle: VehicleInformationData): Int =
        VehicleInformation.createVehicleInformation(
            builder,
            builder.createString(vehicle.sourceShortName),
            builder.createString(vehicle.shortName),
            VehicleInformation.createLogicalLinksVector(builder, vehicle.logicalLinks.map(::writeLink).toIntArray()),
        )

    private fun writeLink(link: LogicalLinkData): Int =
        LogicalLink.createLogicalLink(
            builder,
            builder.createString(link.id),
            builder.createString(link.shortName),
            LogicalLink.createTargetsVector(builder, link.targets.map(::writeTarget).toIntArray()),
            link.protocol.offset(),
            link.protocolStack.offset(),
            link.physicalLinkId.offset(),
            link.physicalLinkType.offset(),
            LogicalLink.createGatewayLinksVector(builder, link.gatewayLinks.map(::writeGatewayRef).toIntArray()),
            LogicalLink.createComParamsVector(builder, link.comParams.map(::writeParameter).toIntArray()),
            link.physicalLinkSourceShortName.offset(),
            link.isGateway,
            link.gatewaySemantic.offset(),
        )

    private fun writeTarget(target: DiagnosticLayerTarget): Int =
        targetOffsets.getOrPut(target) {
            val kind =
                when (target.kind) {
                    TargetKind.BASE_VARIANT -> DiagnosticLayerKind.BASE_VARIANT
                    TargetKind.FUNCTIONAL_GROUP -> DiagnosticLayerKind.FUNCTIONAL_GROUP
                }
            DiagnosticLayerRef.createDiagnosticLayerRef(
                builder,
                builder.createString(target.databaseId),
                kind,
                builder.createString(target.shortName),
            )
        }

    private fun writeGatewayRef(link: LogicalLinkKey): Int =
        LogicalLinkRef.createLogicalLinkRef(builder, builder.createString(link.sourceShortName), builder.createString(link.id))

    private fun writeParameter(param: VehicleComParamData): Int =
        CommunicationParameter.createCommunicationParameter(
            builder,
            builder.createString(param.shortName),
            builder.createString(param.definitionDocument),
            builder.createString(param.definitionId),
            param.value?.let(::writeValue) ?: 0,
        )

    private fun writeValue(value: VehicleParamValue): Int =
        ParameterValue.createParameterValue(
            builder,
            value.shortName.offset(),
            value.simpleValue.offset(),
            value.complexValues?.let { values ->
                ParameterValue.createComplexValuesVector(builder, values.map(::writeValue).toIntArray())
            } ?: 0,
        )

    private fun String?.offset(): Int = this?.let(builder::createString) ?: 0
}
