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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import org.eclipse.persistence.jaxb.JAXBContextFactory
import schema.odx.ODX
import java.io.File
import java.util.logging.Logger

class VehicleDataCommand : CliktCommand(name = "vehicle-data") {
    val inputFiles: List<File> by argument(name = "pdx-files")
        .file(mustExist = true, mustBeReadable = true, canBeDir = false)
        .help("PDX files supplying vehicle information and the referenced diagnostic descriptions")
        .multiple(required = true)

    val outputFile: File by option("-o", "--output")
        .help("Output path ending in .vehicle-data")
        .file(canBeDir = false)
        .required()

    override fun help(context: Context): String =
        "Writes vehicle information and logical links from a PDX set to a VehicleData FlatBuffer companion."

    override fun run() {
        val context = JAXBContextFactory.createContext(arrayOf(ODX::class.java), null)
        try {
            FileConverter(Logger.getLogger("vehicle-data"), context).writeVehicleData(inputFiles, outputFile)
        } catch (error: Exception) {
            throw CliktError(error.message ?: "Could not write the vehicle companion")
        }
    }
}
