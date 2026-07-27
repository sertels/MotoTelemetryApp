package com.example.mototelemetryapp

// Turns a raw DTC like "P0133" into something a rider can act on.
//
// A full DTC database is tens of thousands of entries, most of them manufacturer-specific and
// none of them published for this bike. Rather than ship a partial table that silently says
// nothing for anything it doesn't know, this pairs a small table of the common generic codes with
// a structural decode that works for *any* code: SAE J2012 fixes what the letter and the first
// digits mean, so even an unrecognised code can be placed on a system and identified as
// standardised or manufacturer-specific. An honest "manufacturer-specific engine code, look it up
// against Voge documentation" beats a blank space.
object DtcDescriptions {

    data class Description(
        // One line for the collapsed row.
        val summary: String,
        // The longer explanation revealed on tap.
        val detail: String,
        // False when this came from the structural decode rather than the table, so the UI can be
        // honest that it doesn't actually know this specific code.
        val isKnownCode: Boolean
    )

    fun describe(code: String): Description {
        val normalized = code.trim().uppercase()
        KNOWN[normalized]?.let { return it }
        return structuralDescription(normalized)
    }

    private fun structuralDescription(code: String): Description {
        if (code.length < 2) {
            return Description("Unrecognised code", "This doesn't look like a standard trouble code.", false)
        }

        val system = when (code[0]) {
            'P' -> "Powertrain (engine/transmission)"
            'C' -> "Chassis (ABS, brakes, suspension)"
            'B' -> "Body (lights, instruments, electrics)"
            'U' -> "Network (module communication)"
            else -> "Unknown system"
        }

        // Second character: 0 and 2 are SAE-standardised, 1 and 3 are the manufacturer's own.
        val manufacturerSpecific = code.getOrNull(1) in listOf('1', '3')
        val origin = if (manufacturerSpecific) {
            "This is a manufacturer-specific code, so its exact meaning is defined by Voge rather " +
                "than the OBD-II standard. You'll need Voge documentation or a dealer tool to read it precisely."
        } else {
            "This is a generic OBD-II code, standardised across manufacturers, but this app doesn't " +
                "carry a description for this particular one."
        }

        val subsystem = if (code[0] == 'P') SUBSYSTEMS[code.getOrNull(2)] else null

        return Description(
            summary = listOfNotNull(system, subsystem).joinToString(" · "),
            detail = "$origin\n\nSystem: $system" + (subsystem?.let { "\nLikely area: $it" } ?: ""),
            isKnownCode = false
        )
    }

    // Third character of a P code, per SAE J2012.
    private val SUBSYSTEMS = mapOf(
        '1' to "Fuel and air metering",
        '2' to "Fuel and air metering (injector circuit)",
        '3' to "Ignition system or misfire",
        '4' to "Emission controls",
        '5' to "Idle speed and vehicle speed control",
        '6' to "Computer output circuit",
        '7' to "Transmission",
        '8' to "Transmission"
    )

    // The generic codes most likely to actually turn up. Kept deliberately short - each entry is a
    // claim about what a fault means, and a wrong claim about a bike is worse than no claim.
    private val KNOWN: Map<String, Description> = mapOf(
        "P0131" to Description(
            "O2 sensor low voltage · Bank 1 Sensor 1",
            "The upstream oxygen sensor is reading a persistently lean signal. Often the sensor " +
                "itself, but an exhaust leak ahead of it or an intake air leak can cause the same reading.",
            true
        ),
        "P0133" to Description(
            "O2 sensor slow response · Bank 1 Sensor 1",
            "The upstream oxygen sensor is switching between rich and lean more slowly than the ECU " +
                "expects. Usually an ageing sensor; contamination or an exhaust leak can also do it. " +
                "The bike will run, but fuelling accuracy and economy suffer.",
            true
        ),
        "P0134" to Description(
            "O2 sensor no activity · Bank 1 Sensor 1",
            "The ECU sees no usable signal from the upstream oxygen sensor at all - commonly a failed " +
                "sensor, its heater circuit, or the wiring to it.",
            true
        ),
        "P0171" to Description(
            "System too lean · Bank 1",
            "Fuel trim has hit its limit trying to add fuel, meaning the engine is getting more air " +
                "than the ECU expects. Typical causes are an intake or vacuum leak, a dirty air filter, " +
                "a weak fuel pump, or clogged injectors.",
            true
        ),
        "P0172" to Description(
            "System too rich · Bank 1",
            "Fuel trim has hit its limit trying to remove fuel. Often a leaking injector, excessive " +
                "fuel pressure, or a restricted air intake.",
            true
        ),
        "P0300" to Description(
            "Random or multiple cylinder misfire",
            "The ECU has detected misfires that aren't isolated to one cylinder. Worth attention soon - " +
                "sustained misfiring can damage the catalytic converter. Common causes are ignition " +
                "components, fuelling, or low compression.",
            true
        ),
        "P0301" to Description(
            "Cylinder 1 misfire",
            "Cylinder 1 is misfiring. Usually its spark plug, coil, or injector - swapping the coil or " +
                "plug with the other cylinder and seeing whether the code follows is the usual first test.",
            true
        ),
        "P0302" to Description(
            "Cylinder 2 misfire",
            "Cylinder 2 is misfiring. Usually its spark plug, coil, or injector - swapping the coil or " +
                "plug with the other cylinder and seeing whether the code follows is the usual first test.",
            true
        ),
        "P0217" to Description(
            "Engine overheating",
            "The ECU has recorded a coolant temperature above its limit. Stop and let the engine cool " +
                "before riding on; check coolant level, the radiator fan, and the thermostat.",
            true
        ),
        "P0562" to Description(
            "System voltage low",
            "Charging voltage is below what the ECU expects. Commonly a failing regulator/rectifier, " +
                "stator, or a battery near the end of its life - a classic motorcycle failure.",
            true
        ),
        "P0563" to Description(
            "System voltage high",
            "Charging voltage is above what the ECU expects, which usually points at the " +
                "regulator/rectifier. Sustained overvoltage can damage electronics and the battery.",
            true
        ),
        "P0505" to Description(
            "Idle control system",
            "The ECU can't hold the target idle speed. Often a dirty throttle body or idle air passage, " +
                "or an intake leak.",
            true
        )
    )
}
