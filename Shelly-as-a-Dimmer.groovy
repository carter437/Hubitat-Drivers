/**
 *
 *  Shelly Switch Relay Driver
 *
 *  Copyright © 2018-2019 Scott Grayban
 *  Copyright © 2020 Allterco Robotics US
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * Hubitat is the Trademark and intellectual Property of Hubitat Inc.
 * Shelly is the Trademark and Intellectual Property of Allterco Robotics Ltd
 *
 *-------------------------------------------------------------------------------------------------------------------
 *
 * See all the Shelly Products at https://shelly.cloud/
 * Supported devices are:
 *   Dimmer 2
 *
 */

import groovy.json.*
import groovy.transform.Field

def setVersion(){
    state.Version = "3.0.5"
    state.InternalName = "ShellyAsADimmer"
}

metadata {
    definition (
            name: "Shelly Dimmer",
            namespace: "sgrayban",
            author: "Scott Grayban",
            importUrl: "https://raw.githubusercontent.com/ShellyUSA/Hubitat-Drivers/master/Shelly-as-a-Switch.groovy"
    )
            {
                capability "Actuator"
                capability "Sensor"
                capability "Refresh"
                capability "Switch"
                capability "SwitchLevel"
                capability "RelaySwitch"
                capability "Polling"
                capability "PowerMeter"
                capability "VoltageMeasurement"
                capability "SignalStrength"
                capability "TemperatureMeasurement"

                attribute "FW_Update_Needed", "string"
                attribute "LastRefresh", "string"
                attribute "power", "number"
                attribute "overpower", "string"
                attribute "internal_tempC", "string"
                attribute "internal_tempF", "string"
                attribute "DeviceOverTemp", "string"
                attribute "MAC", "string"
                attribute "Primary_IP", "string"
                attribute "Primary_SSID", "string"
                attribute "Secondary_IP", "string"
                attribute "Secondary_SSID", "string"
                attribute "WiFiSignal", "string"
                attribute "Cloud", "string"
                attribute "Cloud_Connected", "string"
                attribute "energy", "number"
                attribute "DeviceType", "string"
                attribute "eMeter", "number"
                attribute "reactive", "number"
                attribute "DeviceName", "string"
                attribute "NTPServer", "string"

                command "RebootDevice"
                command "UpdateDeviceFW" // ota?update=1
                //command "updatecheck" // Only used for development
            }

    preferences {
        def refreshRate = [:]
        refreshRate << ["1 min" : "Refresh every minute"]
        refreshRate << ["5 min" : "Refresh every 5 minutes"]
        refreshRate << ["15 min" : "Refresh every 15 minutes"]
        refreshRate << ["30 min" : "Refresh every 30 minutes"]
        refreshRate << ["manual" : "Manually or Polling Only"]

        input("ip", "string", title:"IP", description:"Shelly IP Address", defaultValue:"" , required: true)
        input name: "username", type: "text", title: "Username:", description: "(blank if none)", required: false
        input name: "password", type: "password", title: "Password:", description: "(blank if none)", required: false

        if (ip != null) { // show settings *after* IP is set as some settings do not apply to all devices

            input "protect", "enum", title:"Prevent accidental off/on", defaultValue: true, options: [Yes:"Yes",No:"No"], required: true

            if (getDataValue("model") != "SHEM" && getDataValue("model") != "SHEM-3") input("refresh_Rate", "enum", title: "Device Refresh Rate", description:"<font color=red>!!WARNING!!</font><br>DO NOT USE if you have over 50 Shelly devices.", options: refreshRate, defaultValue: "manual")

        } // END IP check

        input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
        input name: "debugParse", type: "bool", title: "Enable JSON parse logging?", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "Shellyinfo", type: "text", title: "<center><font color=blue>Info Box</font><br>Shelly API docs are located</center>",
                description: "<center><br><a href='http://shelly-api-docs.shelly.cloud/' title='shelly-api-docs.shelly.cloud' target='_blank'>[here]</a></center>"
    }
}

def initialize() {
    log.info "initialize"
    if (txtEnable) log.info "initialize"
}

def installed() {
    log.debug "Installed"
    state.DeviceName = "NotSet"
    state.RelayName = "NotSet"
}

def uninstalled() {
    unschedule()
    log.debug "Uninstalled"
}

def updated() {
    if (txtEnable) log.info "Preferences updated..."
    log.warn "Debug logging is: ${debugOutput == true}"
    log.warn "Switch protection is: ${settings?.protect}"
    unschedule()
    dbCleanUp()
    if (ip != null) { // Don't set until IP is saved
        sendSwitchCommand "/settings?sntp_server=${ntp_server}"
// Set device and relay name
        sendSwitchCommand "/settings?name=${devicename}"
    }

    switch(refresh_Rate) {
        case "1 min" :
            runEvery1Minute(autorefresh)
            break
        case "5 min" :
            runEvery5Minutes(autorefresh)
            break
        case "15 min" :
            runEvery15Minutes(autorefresh)
            break
        case "30 min" :
            runEvery30Minutes(autorefresh)
            break
        case "manual" :
            unschedule(autorefresh)
            log.info "Autorefresh disabled"
            break
    }
    if (txtEnable) log.info ("Auto Refresh set for every ${refresh_Rate} minute(s).")

    if (debugOutput) runIn(1800,logsOff) //Off in 30 minutes
    if (debugParse) runIn(300,logsOff) //Off in 5 minutes

    state.LastRefresh = new Date().format("YYYY/MM/dd \n HH:mm:ss", location.timeZone)

    version()
    refresh()
}

private dbCleanUp() {
    state.remove("version")
    state.remove("Version")
    state.remove("ShellyfwUpdate")
    state.remove("power")
    state.remove("overpower")
    state.remove("dcpower")
    state.remove("max_power")
    state.remove("internal_tempC")
    state.remove("Status")
    state.remove("max_power")
    state.remove("RelayName")
}

def refresh(){
    if (ip != null) { // Don't set until IP is saved
        logDebug "Shelly Status called"
        getSettings()
        def params = [uri: "http://${username}:${password}@${ip}/status"]

        try {
            httpGet(params) {
                resp -> resp.headers.each {
                    logJSON "Response: ${it.name} : ${it.value}"
                }
                    obs = resp.data
                    logJSON "params: ${params}"
                    logJSON "response contentType: ${resp.contentType}"
                    logJSON "response data: ${resp.data}"

                    if (obs.temperature != null) sendEvent(name: "internal_tempC", value: obs.temperature)
                    if (obs.tmp != null) {
                        sendEvent(name: "internal_tempC", unit: "C", value: obs.tmp.tC)
                        sendEvent(name: "internal_tempF", unit: "F", value: obs.tmp.tF)
                    }
                    if (obs.overtemperature != null) sendEvent(name: "DeviceOverTemp", value: obs.overtemperature)

                    if (obs.wifi_sta != null) {
                        state.rssi = obs.wifi_sta.rssi
                        state.ssid = obs.wifi_sta.ssid
                        state.ip = obs.wifi_sta.ip
                        sendEvent(name: "Primary_SSID", value: state.ssid)
                        sendEvent(name: "Primary_IP", value: state.ip)
                    }

/*
-30 dBm	Excellent | -67 dBm	Good | -70 dBm	Poor | -80 dBm	Weak | -90 dBm	Dead
*/
                    signal = state.rssi
                    if (signal <= 0 && signal >= -70) {
                        sendEvent(name:  "WiFiSignal", value: "<font color='green'>Excellent</font>", isStateChange: true);
                    } else
                    if (signal < -70 && signal >= -80) {
                        sendEvent(name:  "WiFiSignal", value: "<font color='green'>Good</font>", isStateChange: true);
                    } else
                    if (signal < -80 && signal >= -90) {
                        sendEvent(name: "WiFiSignal", value: "<font color='yellow'>Poor</font>", isStateChange: true);
                    } else
                    if (signal < -90 && signal >= -100) {
                        sendEvent(name: "WiFiSignal", value: "<font color='red'>Weak</font>", isStateChange: true);
                    }

                    state.mac = obs.mac
                    sendEvent(name: "MAC", value: state.mac)
                    sendEvent(name: "rssi", value: state.rssi)


// Device FW Updates
                    state.has_update = obs.has_update
                    if (state.has_update == true) {
                        if (txtEnable) log.info "sendEvent NEW SHELLY FIRMWARE"
                        sendEvent(name: "FW_Update_Needed", value: "<font color='red'>FIRMWARE Update Required</font>")
                    }

                    if (state.has_update == false) {
                        if (txtEnable) log.info "sendEvent Device FW is current"
                        sendEvent(name: "FW_Update_Needed", value: "<font color='green'>Device FW is current</font>")
                    }

// Cloud
                    state.cloud = obs.cloud.enabled
                    if (state.cloud == true) {
                        sendEvent(name: "Cloud", value: "<font color='green'>Enabled</font>")
                    } else {
                        sendEvent(name: "Cloud", value: "<font color='red'>Disabled</font>")
                    }

                    state.cloudConnected = obs.cloud.connected
                    if (state.cloudConnected == true) {
                        sendEvent(name: "Cloud_Connected", value: "<font color='green'>Connected</font>")
                    } else {
                        sendEvent(name: "Cloud_Connected", value: "<font color='red'>Not Connected</font>")
                    }

            } // End try
        } catch (e) {
            log.error "something went wrong: $e"
        }
    } // End if !==ip      
} // End Refresh Status


// Get shelly device type
def getSettings(){
    if (ip != null) { // Don't set until IP is saved
        logDebug "Get Shelly Settings"
        def paramsSettings = [uri: "http://${username}:${password}@${ip}/settings"]

        try {
            httpGet(paramsSettings) {
                respSettings -> respSettings.headers.each {
                    logJSON "ResponseSettings: ${it.name} : ${it.value}"
                }
                    obsSettings = respSettings.data

                    logJSON "params: ${paramsSettings}"
                    logJSON "response contentType: ${respSettings.contentType}"
                    logJSON "response data: ${respSettings.data}"

                    state.DeviceType = obsSettings.device.type
                    if (state.DeviceType == "SHDM-2") sendEvent(name: "DeviceType", value: "Shelly Dimmer 2")
                    state.ShellyHostname = obsSettings.device.hostname
                    state.sntp_server = obsSettings.sntp.server
                    sendEvent(name: "NTPServer", value: state.sntp_server)

//Get Device name
                    if (obsSettings.name != "NotSet") {
                        state.DeviceName = obsSettings.name
                        sendEvent(name: "DeviceName", value: state.DeviceName)
                        updateDataValue("DeviceName", state.DeviceName)
                        if (txtEnable) log.info "DeviceName is ${obsSettings.name}"
                    } else if (obsSettings.name != null) {
                        state.DeviceName = "NotSet"
                        sendEvent(name: "DeviceName", value: state.DeviceName)
                        if (txtEnable) log.info "DeviceName is ${obsSettings.name}"
                    }

                    if (obsSettings.wifi_sta1 != null) {
                        state.rssi = obsSettings.wifi_sta1.rssi
                        state.Secondary_ssid = obsSettings.wifi_sta1.ssid
                        state.Secondary_IP = obsSettings.wifi_sta1.ip
                        if (obsSettings.wifi_sta1.enabled == true) sendEvent(name: "Secondary_SSID", value: state.Secondary_ssid)
                        if (state.Secondary_IP != null) sendEvent(name: "Secondary_IP", value: state.Secondary_IP)
                    }


                    updateDataValue("model", state.DeviceType)
                    updateDataValue("ShellyHostname", state.ShellyHostname)
                    updateDataValue("ShellyIP", obsSettings.wifi_sta.ip)
                    updateDataValue("ShellySSID", obsSettings.wifi_sta.ssid)
                    updateDataValue("manufacturer", "Allterco Robotics")
                    updateDataValue("MAC", state.mac)
                    updateDataValue("DeviceName", state.DeviceName)

            } // End try
        } catch (e) {
            log.error "something went wrong: $e"
        }
    } // End if !==ip      
} // End Refresh Status


def setLevel(level) {
    if (protect == "No") {
        logDebug "Executing switch.on"
        sendSwitchCommand "/light/0?brightness=${level}"
    }
}

def setLevel(level,duration) {
    setLevel(level)
}

//switch.on
def on() {
    if (protect == "No") {
        logDebug "Executing switch.on"
        sendSwitchCommand "/light/0?turn=on"
    }
    if (protect == "Yes") {
        sendEvent(name: "switch", value: "<font color='red'>LOCKED</font>")
        runIn(1, refresh)
    }
}

//switch.off
def off() {
    if (protect == "No") {
        logDebug "Executing switch.off"
        sendSwitchCommand "/light/0?turn=off"
    }
    if (protect == "Yes") {
        sendEvent(name: "switch", value: "<font color='red'>LOCKED</font>")
        runIn(1, refresh)
    }
}

def ping() {
    logDebug "ping"
    poll()
}

def logsOff(){
    log.warn "debug logging auto disabled..."
    device.updateSetting("debugOutput",[value:"false",type:"bool"])
    device.updateSetting("debugParse",[value:"false",type:"bool"])
}

def autorefresh() {
    if (locale == "UK") {
        logDebug "Get last UK Date DD/MM/YYYY"
        state.LastRefresh = new Date().format("d/MM/YYYY \n HH:mm:ss", location.timeZone)
        sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
    }
    if (locale == "US") {
        logDebug "Get last US Date MM/DD/YYYY"
        state.LastRefresh = new Date().format("MM/d/YYYY \n HH:mm:ss", location.timeZone)
        sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
    }
    if (txtEnable) log.info "Executing 'auto refresh'" //RK

    refresh()
}

private logJSON(msg) {
    if (settings?.debugParse || settings?.debugParse == null) {
        log.info "$msg"
    }
}

private logDebug(msg) {
    if (settings?.debugOutput || settings?.debugOutput == null) {
        log.debug "$msg"
    }
}

// handle commands
//RK Updated to include last refreshed
def poll() {
    if (locale == "UK") {
        logDebug "Get last UK Date DD/MM/YYYY"
        state.LastRefresh = new Date().format("d/MM/YYYY \n HH:mm:ss", location.timeZone)
        sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
    }
    if (locale == "US") {
        logDebug "Get last US Date MM/DD/YYYY"
        state.LastRefresh = new Date().format("MM/d/YYYY \n HH:mm:ss", location.timeZone)
        sendEvent(name: "LastRefresh", value: state.LastRefresh, descriptionText: "Last refresh performed")
    }
    if (txtEnable) log.info "Executing 'poll'" //RK
    refresh()
}

def sendSwitchCommand(action) {
    if (txtEnable) log.info "Calling ${action}"
    def params = [uri: "http://${username}:${password}@${ip}/${action}"]
    try {
        httpPost(params) {
            resp -> resp.headers.each {
                logDebug "Response: ${it.name} : ${it.value}"
            }
        } // End try

    } catch (e) {
        log.error "something went wrong: $e"
    }
    runIn(2, refresh)
}

def RebootDevice() {
    if (txtEnable) log.info "Rebooting Device"
    def params = [uri: "http://${username}:${password}@${ip}/reboot"]
    try {
        httpPost(params) {
            resp -> resp.headers.each {
                logDebug "Response: ${it.name} : ${it.value}"
            }
        } // End try

    } catch (e) {
        log.error "something went wrong: $e"
    }
    runIn(15,refresh)
}

def UpdateDeviceFW() {
    if (txtEnable) log.info "Updating Device FW"
    def params = [uri: "http://${username}:${password}@${ip}/ota?update=1"]
    try {
        httpPost(params) {
            resp -> resp.headers.each {
                logDebug "Response: ${it.name} : ${it.value}"
            }
        } // End try

    } catch (e) {
        log.error "something went wrong: $e"
    }
    runIn(30,refresh)
}

// Check Version   ***** with great thanks and acknowlegment to Cobra (github CobraVmax) for his original code **************
def version(){
    updatecheck()
    schedule("0 0 18 1/1 * ? *", updatecheck) // Cron schedule
//	schedule("0 0/1 * 1/1 * ? *", updatecheck) // Test Cron schedule
}

def updatecheck(){
    setVersion()
    def paramsUD = [uri: "https://raw.githubusercontent.com/ShellyUSA/Hubitat-Drivers/master/resources/version.json", contentType: "application/json; charset=utf-8"]
    try {
        httpGet(paramsUD) { respUD ->
            if (debugParse) log.debug " Version Checking - Response Data: ${respUD.data}"
            def copyrightRead = (respUD.data.copyright)
            state.Copyright = copyrightRead
            def newVerRaw = (respUD.data.versions.Driver.(state.InternalName))
            def newVer = (respUD.data.versions.Driver.(state.InternalName).replace(".", ""))
            def currentVer = state.Version.replace(".", "")
            state.UpdateInfo = (respUD.data.versions.UpdateInfo.Driver.(state.InternalName))
            state.author = (respUD.data.author)
            state.icon = (respUD.data.icon)
            if(newVer == "NLS"){
                state.DriverStatus = "<b>** This driver is no longer supported by $state.author  **</b>"
                log.warn "** This driver is no longer supported by $state.author **"
            } else
            if(newVer == "BETA"){
                state.Status = "<b>** THIS IS BETA CODE  **</b>"
                log.warn "** BETA CODE **"
            } else
            if(currentVer < newVer){
                state.DriverStatus = "<b>New Version Available (Version: $newVerRaw)</b>"
                log.warn "** There is a newer version of this driver available  (Version: $newVerRaw) **"
                log.warn "** $state.UpdateInfo **"
            } else
            if(currentVer > newVer){
                state.DriverStatus = "<b>You are using a Test version of this Driver (Version: $state.Version)</b>"
            } else {
                state.DriverStatus = "Current"
                log.info "You are using the current version of this driver"
            }
        } // httpGet
    } // try

    catch (e) {
        log.error "Something went wrong: CHECK THE JSON FILE AND IT'S URI -  $e"
    }
    if(state.DriverStatus == "Current"){
        state.UpdateInfo = "Up to date"
        sendEvent(name: "DriverUpdate", value: state.UpdateInfo)
        sendEvent(name: "DriverStatus", value: state.DriverStatus)
    } else {
        sendEvent(name: "DriverUpdate", value: state.UpdateInfo)
        sendEvent(name: "DriverStatus", value: state.DriverStatus)
    }

    sendEvent(name: "DriverAuthor", value: "sgrayban")
    sendEvent(name: "DriverVersion", value: state.Version)
}
