/**
 *  Copyright 2020 Magnus Solvåg
 */
metadata {
	definition (name: "Heatit Z-TRM2fx", namespace: "ms", author: "Magnus Solvåg") {
		capability "Actuator"
		capability "Temperature Measurement"
		capability "Thermostat Mode"
        capability "Thermostat Heating Setpoint"
        capability "Thermostat Operating State"
		capability "Refresh"
		capability "Sensor"

        command "switchMode"
		command "lowerHeatingSetpoint"
		command "raiseHeatingSetpoint"

        fingerprint zw:"Ls", type:"0806", mfr:"019B", prod:"0003", model:"0202", deviceJoinName:"HeatIt Z-TRM2fx"
	}

	tiles(scale:2) {
		multiAttributeTile(name:"temperature", type:"thermostat", width:6, height:4) {
            tileAttribute("device.temperature", key: "PRIMARY_CONTROL") {
                attributeState("temperature", label:'${currentValue}°', defaultState: true)
            }
        	tileAttribute("device.heatingSetpoint", key: "VALUE_CONTROL") {
                attributeState("VALUE_UP", action: "raiseHeatingSetpoint")
                attributeState("VALUE_DOWN", action: "lowerHeatingSetpoint")
            }
            tileAttribute("device.thermostatOperatingState", key: "OPERATING_STATE") {
                attributeState("idle", backgroundColor:"#00A0DC", label: "Idle")
                attributeState("heating", backgroundColor:"#e86d13", label: "Heating")
            }
            tileAttribute("device.thermostatOperatingState", key: "THERMOSTAT_MODE") {
                attributeState("idle", label: "Idle")
                attributeState("heating", label: "Heating")
            }
		}
        standardTile("mode", "device.thermostatMode", width:4, height:1, inactiveLabel: false, decoration: "flat") {
			state "heat", action:"switchMode", nextState:"...", label: "Comfort"
            state "eco", action:"switchMode", nextState:"...", label: "Eco"
            state "off", action:"switchMode", nextState:"...", label: "Off"
			state "...", label: "...",nextState:"...", backgroundColor:"#eeeeee"
		}
		standardTile("refresh", "device.thermostatMode", width:2, height:1, inactiveLabel: false, decoration: "flat") {
			state "default", action:"refresh", icon:"st.secondary.refresh"
		}
        controlTile("levelSliderControl", "device.heatingSetpoint", "slider", height: 1, width: 6, inactiveLabel: false, range:"(10..40)") {
			state "level", action:"setHeatingSetpoint"
		}
		main "temperature"
		details(["temperature", "mode", "refresh", "levelSliderControl"])
	}
}

// List of supported capabilities
private getCommandClassCapabilities() {
    [
        0x85:1, // Association
		0x59:1, // Association Group Info
		0x8E:2, // Multi Channel Association (supports v3)
        0x86:1, // Version (suppports v3)
        0x70:2, // Configuration (supports v3)
        0x72:2, // Manufacturer Specific
        0x60:3, // Multi Channel (supports v4)
        0x20:1, // Basic (supports v2)
        0x31:5, // Sensor Multilevel
        0x43:2, // Thermostat Setpoint (supports v3)
        0x40:2, // Thermostat Mode (supports v3)
        0x25:1, // Switch Binary
        0x32:3, // Meter
        0x98:1, // Security
        0x80:1, // Battery
    ]
}

private getSupportedModes(){
	["off": 0, "heat": 1, "eco": 11]
}

def installed() {
	log.info "installed"
	initialize()
}

def updated() {
	log.info "updated"
	initialize()
}

def initialize() {
	log.info "initialize"
   
	// Device-Watch simply pings if no device events received for 32min(checkInterval)
	sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
	unschedule()
	runEvery5Minutes("pollDevice")
	pollDevice()
}

def parse(String description) {
    def result = null
    def cmd = zwave.parse(description)
    if (cmd) {
        result = zwaveEvent(cmd)
    } else {
        log.warn "Non-parsed event: ${description}"
    }
     return result
}

// Event Generation
def zwaveEvent(physicalgraph.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd, ep = null) {
	log.trace "ZWAVE: ThermostatSetpointReport, cmd: ${cmd}"
    
	def cmdScale = cmd.scale == 1 ? "F" : "C"
	def setpoint = getTempInLocalScale(cmd.scaledValue, cmdScale)
	def unit = getTemperatureScale()
    def result = []
  
	switch (cmd.setpointType) {
		case 1:
        case 11:
			result << createEvent(name: "heatingSetpoint", value: setpoint, unit: unit, displayed: false)
			break;
		default:
			log.warn "unknown setpointType $cmd.setpointType"
			return
	}
    
	// So we can respond with same format
	state.size = cmd.size
	state.scale = cmd.scale
	state.precision = cmd.precision

	return result
}

def zwaveEvent(physicalgraph.zwave.commands.sensormultilevelv2.SensorMultilevelReport cmd, ep = null) {
	log.trace "ZWAVE: SensorMultilevelReport, cmd: ${cmd}"
	def map = [:]
    
	if (cmd.sensorType == 1) {
		map.value = getTempInLocalScale(cmd.scaledSensorValue, cmd.scale == 1 ? "F" : "C")
		map.unit = getTemperatureScale()
		map.name = "temperature"
	}
   
	return createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport cmd, ep = null) {
	log.trace "ZWAVE: ThermostatOperatingStateReport, cmd: ${cmd}"
	def map = [name: "thermostatOperatingState"]
    
	switch (cmd.operatingState) {
		case physicalgraph.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_IDLE:
			map.value = "idle"
			break
		case physicalgraph.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_HEATING:
			map.value = "heating"
			break
		case physicalgraph.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_PENDING_HEAT:
			map.value = "pending heat"
			break
       	default:
        	log.warn "Unknown operating state: $cmd.operatingState"
            break
	}

	return createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatmodev2.ThermostatModeReport cmd, ep = null) {
	log.trace "ZWAVE: ThermostatModeReport, cmd: ${cmd}"
	def map = [name: "thermostatMode", data:[supportedThermostatModes: state.supportedModes]]

	switch (cmd.mode) {
		case physicalgraph.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_OFF:
			map.value = "off"
			break
		case physicalgraph.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_HEAT:
			map.value = "heat"
			break
		case physicalgraph.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_ENERGY_SAVE_HEAT:
            map.value = "eco"
            break
       	default:
        	log.warn "Unknown operating mode: ${cmd.mode}"
            break
	}

    return createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	log.trace "ZWAVE: ManufacturerSpecificReport, cmd: ${cmd}"
    
	if (cmd.manufacturerName) {
		updateDataValue("manufacturer", cmd.manufacturerName)
	}
	if (cmd.productTypeId) {
		updateDataValue("productTypeId", cmd.productTypeId.toString())
	}
	if (cmd.productId) {
		updateDataValue("productId", cmd.productId.toString())
	}
    return []
}

def zwaveEvent(physicalgraph.zwave.commands.meterv3.MeterReport cmd, ep = null) {
    log.trace "ZWAVE: MeterReport, cmd: ${cmd}"
    
    def result = []
    
	if (cmd.scale == 0) {
		result << createEvent(name: "energy", value: (float) (Math.round(cmd.scaledMeterValue * 10.0) / 10.0), unit: "kWh")
	} else if (cmd.scale == 1) {
		result << createEvent(name: "energykVAh", value: (float) (Math.round(cmd.scaledMeterValue * 10.0) / 10.0), unit: "kVAh")
	} else if (cmd.scale == 2) {
		result << createEvent(name: "power", value: (float) (Math.round(cmd.scaledMeterValue * 10.0) / 10.0), unit: "W")
	} else if (cmd.scale == 4) {
		result << createEvent(name: "voltage", value: (float) (Math.round(cmd.scaledMeterValue * 10.0) / 10.0), unit: "V")
	} else if (cmd.scale == 5) {
		result << createEvent(name: "amperage", value: (float) (Math.round(cmd.scaledMeterValue * 10.0) / 10.0), unit: "A")
	} else {
        log.warn "Unknown Meter Report: $cmd"
    }
    
    result
}

def zwaveEvent(physicalgraph.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, ep = null) {
	log.trace "ZWAVE: SwitchBinaryReport, cmd: ${cmd}"
    setOperatingState(cmd)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd, ep = null) {
	log.trace "ZWAVE: SwitchBinaryReport, cmd: ${cmd}"
 	if (ep){
        setOperatingState(cmd)
   	}
}

def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {    
	def encapsulatedCommand = cmd.encapsulatedCommand(commandClassCapabilities)
	if (encapsulatedCommand) {
		return zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
	} else {
        log.warn "multichannelv3.MultiChannelCmdEncap: unrecognized command $cmd"
    }
    return []
}

def zwaveEvent(physicalgraph.zwave.Command cmd, ep = null) {
	log.warn "ZWAVE: Unhandled event, cmd: ${cmd}, ep: ${ep}"
    return []
}

// Multi channel encapsulation of a command with a target endpoint and an optional bitmask addressing
private encap(cmd, ep, bitMask = false) {
    zwave.multiChannelV3.multiChannelCmdEncap(bitAddress: bitMask, destinationEndPoint: ep).encapsulate(cmd)
}

// Command Implementations
def refresh() {
	log.info "CMD: refresh"
	runIn(2, "pollDevice", [overwrite: true])
}

def pollDevice() {
	log.info "FUNC: pollDevice"
    def type = supportedModes[device.currentValue("thermostatMode")] ?: 1
	sendHubCommand([
		zwave.thermostatModeV2.thermostatModeGet(),
		encap(zwave.sensorMultilevelV5.sensorMultilevelGet(), 3),
		zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: type),
    	encap(zwave.switchBinaryV1.switchBinaryGet(), 4)
    ])
}

def raiseHeatingSetpoint() {
	setHeatingSetpoint(getTempInLocalScale() +1)
}

def lowerHeatingSetpoint() {
	setHeatingSetpoint(getTempInLocalScale() -1)
}

def setHeatingSetpoint(setpoint) {
	log.info "CMD: setHeatingSetpoint, setpoint: ${setpoint}"
   
	def locationScale = getTemperatureScale() 
	def minSetpoint = getTempInDeviceScale(5, "C")
	def maxSetpoint = getTempInDeviceScale(40, "C")
	def limitedValue = enforceSetpointLimits(setpoint)
    def deviceScale = (state.scale == 1) ? "F" : "C"
    
	sendEvent(
        "name": 'heatingSetpoint',
        "value": getTempInLocalScale(limitedValue, deviceScale),
        unit: locationScale, 
        eventType: "ENTITY_UPDATE", 
        displayed: false
    )

    def type = supportedModes[device.currentValue("thermostatMode")] ?: 1
    sendHubCommand([
    	zwave.thermostatSetpointV2.thermostatSetpointSet(setpointType: type, scale: state.scale, precision: state.precision, scaledValue: limitedValue),
        zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: type),
    	encap(zwave.switchBinaryV1.switchBinaryGet(), 4)
    ], 1000)
    
    return []
}

def enforceSetpointLimits(targetValue) {
	def locationScale = getTemperatureScale() 
	def minSetpoint = getTempInDeviceScale(5, "C")
	def maxSetpoint = getTempInDeviceScale(40, "C")
    
    def limitedValue = getTempInDeviceScale(targetValue, "C")
	if (targetValue > maxSetpoint) {
		limitedValue = maxSetpoint
	} else if (targetValue < minSetpoint) {
		limitedValue = minSetpoint
	}
    
    return limitedValue
}

private setOperatingState(cmd) {
    def map =[:]
    if (cmd.value) {
        switch (device.currentValue("thermostatMode")) {
            case "eco":
            case "heat":
            	map = [ name: "thermostatOperatingState", value: "heating" ]
                break
            case "off":
            	map = [ name: "thermostatOperatingState", value: "idle" ]
            	break    
            default:
            	log.warn "Unsupported thermostat operating state: ${device.currentValue("thermostatMode")}"
                break
        }                
    } else {
        map = [ name: "thermostatOperatingState", value: "idle" ]
    }
    
    return createEvent(map)
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	log.info "ping"
	sendHubCommand(encap(zwave.switchBinaryV1.switchBinaryGet(), 4))
}

def switchMode() {
	def currentMode = device.currentValue("thermostatMode")
    log.debug "FUNC: switchMode, currentMode: ${currentMode}"
   
   	def modes = (supportedModes.collect{entry -> entry.key}).drop(1)
    def next = {modes[modes.indexOf(it) + 1] ?: modes[0]}
    def nextMode = next(currentMode)

    setThermostatMode(nextMode)
}

def setThermostatMode(mode) {
	log.info "CMD: setThermostatMode, mode: ${mode}"

    if (supportedModes[mode] >= 0) {
    	log.info "SEND: thermostatModeSet, mode: ${mode}"
		sendHubCommand([
        	zwave.thermostatModeV2.thermostatModeSet(mode: supportedModes[mode]),
            zwave.thermostatModeV2.thermostatModeGet(),
           	zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: supportedModes[mode]),
            encap(zwave.switchBinaryV1.switchBinaryGet(), 4)
        ])
    }
}

// Get stored temperature from currentState in current local scale
def getTempInLocalScale() {
	return getTempInLocalScale('heatingSetpoint')
}

// Get stored temperature from currentState in current local scale
def getTempInLocalScale(state) {
	def temp = device.currentState(state)
	if (temp && temp.value && temp.unit) {
		return getTempInLocalScale(temp.value.toBigDecimal(), temp.unit)
	}
	return 0
}

// get/convert temperature to current local scale
def getTempInLocalScale(temp, scale) {
	if (temp && scale) {
		def scaledTemp = convertTemperatureIfNeeded(temp.toBigDecimal(), scale).toDouble()
		return (getTemperatureScale() == "F" ? scaledTemp.round(0).toInteger() : roundC(scaledTemp))
	}
	return 0
}

def getTempInDeviceScale(state) {
	def temp = device.currentState(state)
	if (temp && temp.value && temp.unit) {
		return getTempInDeviceScale(temp.value.toBigDecimal(), temp.unit)
	}
	return 0
}

def getTempInDeviceScale(temp, scale) {
	if (temp && scale) {
		def deviceScale = (state.scale == 1) ? "F" : "C"
		return (deviceScale == scale) ? temp :
				(deviceScale == "F" ? celsiusToFahrenheit(temp).toDouble().round(0).toInteger() : roundC(fahrenheitToCelsius(temp)))
	}
	return 0
}

def roundC (tempC) {
	return (Math.round(tempC.toDouble() * 2))/2
}