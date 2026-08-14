package com.werewolf

import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

@Serializable
data class Player(
    val id: String, val name: String, val avatar: String,
    var isReady: Boolean = false, var role: Role? = null,
    var trulyTeam: String = "Dân", var team: String = "Dân",
    var heal: Int = 1, var shield: Int = 0, var linked: Int = 0,
    var isDead: Boolean = false, var vote: Int = 0,
    var saveVote: Int = 0, var killVote: Int = 0,
    var werewolfMark: Int = 0, var moonCurse: Int = 0,
    var count: String = "", var foxPower: Int = 1,
    var hunterBullets: Int = 0, var canHunterPassive: Boolean = false,
    var isBloodlust: Boolean = false,
    var isHost: Boolean = false,
    val killersVotedForMe: MutableList<String> = mutableListOf()
)

@Serializable
data class GameAssignment(val playerName: String, val role: String, val description: String)

@Serializable
data class Room(
    val code: String, var hostId: String, val players: MutableList<Player> = mutableListOf(),
    val assignments: MutableMap<String, GameAssignment> = mutableMapOf(),
    val readyPlayers: MutableSet<String> = mutableSetOf(),
    var phase: String = "LOBBY", var dayCount: Int = 0,
    val derpWolfRevengeList: MutableList<String> = mutableListOf(),
    var trialTargetId: String? = null, var currentNightActionIndex: Int = 0,
    var nightActionList: MutableList<String> = mutableListOf(),
    var curserUsedAbility: Boolean = false, var isCurseActiveThisNight: Boolean = false,
    var winner: String? = null, var russianStatus: String? = null,
    var wolfRatio: Double = 0.25, var prophetUses: Int = 0,
    var nightJob: Job? = null, val charmedPlayerIds: MutableSet<String> = mutableSetOf(),
    var witchSaveUsed: Boolean = false, var witchKillUsed: Boolean = false,
    val tempDeadIds: MutableSet<String> = mutableSetOf(), var elderIsDead: Boolean = false,
    var tickSpeed: Int = 1 // Tốc độ trôi thời gian (0: dừng, 1: thường, 2: x2...)
)

@Serializable
data class SocketMessage(val type: String, val data: String)

val rooms = ConcurrentHashMap<String, Room>()
val playerSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

enum class RoleType { WEREWOLF, VILLAGER, SPECIAL }
enum class Role(val type: RoleType) {
    //THIEF(RoleType.SPECIAL),
    THREE_BROTHERS(RoleType.SPECIAL), TWINS(RoleType.SPECIAL), CUPID(RoleType.SPECIAL),
    MOON_MAIDEN(RoleType.SPECIAL), GUARDIAN(RoleType.SPECIAL), WEREWOLF(RoleType.WEREWOLF), CURSER_WEREWOLF(RoleType.WEREWOLF),
    PROPHET_WEREWOLF(RoleType.WEREWOLF), SEER(RoleType.SPECIAL), CELESTIAL_FOX(RoleType.SPECIAL), WITCH(RoleType.SPECIAL),
    PIPER(RoleType.SPECIAL), ELDER(RoleType.SPECIAL), VILLAGER(RoleType.VILLAGER), LYCAN(RoleType.SPECIAL),
    DERP_WOLF(RoleType.WEREWOLF), HUNTER(RoleType.SPECIAL), RUSSIAN(RoleType.SPECIAL)
}

fun calculateProphetUses(count: Int, ratio: Double): Int {
    if (ratio >= 0.33) {
        return when { count >= 26 -> 5; count >= 23 -> 4; count >= 20 -> 3; count >= 17 -> 2; count >= 15 -> 1; else -> 0 }
    } else {
        return when { count >= 23 -> 5; count >= 20 -> 4; count >= 17 -> 3; count >= 14 -> 2; count >= 12 -> 1; else -> 0 }
    }
}

fun getNightActionDuration(room: Room, roleName: String): Int {
    val count = room.players.size; val isNight1 = room.dayCount == 0; val buffer = 3
    if (roleName == "WEREWOLF" || roleName == "CURSER_WEREWOLF" || roleName == "PROPHET_WEREWOLF") {
        val b = if (room.wolfRatio >= 0.33) { when { count >= 46 -> 75; count >= 31 -> 60; count >= 21 -> 50; count >= 16 -> 40; count >= 12 -> 30; else -> 25 } }
        else { when { count >= 46 -> 60; count >= 31 -> 50; count >= 21 -> 40; count >= 16 -> 30; count >= 12 -> 25; else -> 20 } }
        return b + buffer
    } else {
        val b = if (isNight1) { when { count >= 31 -> 40; count >= 16 -> 30; else -> 20 } }
        else { when { count >= 31 -> 30; count >= 16 -> 20; else -> 15 } }
        return b + buffer
    }
}

fun getPhaseDuration(room: Room): Int {
    val count = room.players.size; val isDay1 = room.dayCount <= 1; val buffer = 5
    return when (room.phase) {
        "DAY" -> (if (count >= 46) (if (isDay1) 900 else 420) else if (count >= 31) (if (isDay1) 600 else 300) else if (count >= 21) (if (isDay1) 420 else 240) else if (count >= 16) (if (isDay1) 300 else 180) else if (count >= 12) (if (isDay1) 210 else 150) else (if (isDay1) 120 else 90)) + buffer
        "TRIAL_DEFENSE" -> (if (count >= 21) 60 else if (count >= 12) 45 else 30) + buffer
        "TRIAL_VOTING" -> (if (count >= 46) 45 else if (count >= 31) 30 else if (count >= 21) 20 else if (count >= 12) 15 else 10) + buffer
        "PREPARING" -> 60; "HUNTER_REVENGE" -> 25; else -> 0
    }
}

class Moderator {
    fun distributeRoles(players: List<Player>, wolfRatio: Double): Map<String, GameAssignment> {
        val playerCount = players.size; val roleDeck = mutableListOf<Role>()
        val werewolfCount = (playerCount * wolfRatio).toInt(); val villagerCount = werewolfCount
        var specialSlots = playerCount - werewolfCount - villagerCount
        repeat(werewolfCount) { roleDeck.add(Role.WEREWOLF) }
        repeat(villagerCount) { roleDeck.add(Role.VILLAGER) }
        val available = Role.entries.filter { it.type == RoleType.SPECIAL }.shuffled().toMutableList()
        while (specialSlots > 0 && available.isNotEmpty()) {
            val r = available.removeAt(0)
            if (r == Role.THREE_BROTHERS) { if (specialSlots >= 3) { repeat(3) { roleDeck.add(r) }; specialSlots -= 3 } }
            else if (r == Role.TWINS) { if (specialSlots >= 2) { repeat(2) { roleDeck.add(r) }; specialSlots -= 2 } }
            else { roleDeck.add(r); specialSlots -= 1 }
        }
        while (specialSlots > 0) { roleDeck.add(Role.VILLAGER); specialSlots-- }
        roleDeck.shuffle()
        return players.zip(roleDeck).associate { (p, r) ->
            p.role = r; p.shield = if (r == Role.ELDER) 1 else 0
            if (r == Role.HUNTER) { p.hunterBullets = if (wolfRatio >= 0.33) 1 else 0; p.canHunterPassive = true }
            if (r.type == RoleType.WEREWOLF) { p.trulyTeam = "Sói"; p.team = "Sói" }
            else if (r == Role.LYCAN) { p.trulyTeam = "Sói"; p.team = "Dân" }
            else if (r == Role.PIPER) { p.trulyTeam = "piper"; p.team = "Dân" }
            else { p.trulyTeam = "Dân"; p.team = "Dân" }
            p.id to GameAssignment(p.name, r.name, r.name)
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    
    // KEEP-ALIVE SYSTEM (LÁCH LUẬT RENDER)
    GlobalScope.launch {
        while (true) {
            try {
                // Cứ 10 phút tự "khều" mình một cái để không bị ngủ đông
                delay(600000) 
                val url = java.net.URL("https://monsoila.onrender.com/home")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                val code = connection.responseCode
                println("> [Keep-Alive] Ping server thành công: $code")
            } catch (e: Exception) {
                println("> [Keep-Alive] Ping lỗi (Có thể server đang khởi động): ${e.message}")
            }
        }
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(WebSockets) { pingPeriod = Duration.ofSeconds(15); timeout = Duration.ofSeconds(15); contentConverter = KotlinxWebsocketSerializationConverter(Json) }
        val moderator = Moderator()
        routing {
            staticResources("/", "static")
            
            // FALLBACK ROUTES FOR SPA VIRTUAL NAVIGATION
            listOf("/home", "/dashboard", "/gallery", "/lobby", "/profile", "/dev-login").forEach { path ->
                get(path) {
                    val html = javaClass.classLoader.getResource("static/index.html")?.readBytes()
                    if (html != null) {
                        call.respondBytes(html, io.ktor.http.ContentType.Text.Html)
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }

            // Route cho phòng Lobby cụ thể: /lobby/code=xxxxxx
            get("/lobby/code={code}") {
                val html = javaClass.classLoader.getResource("static/index.html")?.readBytes()
                if (html != null) {
                    call.respondBytes(html, io.ktor.http.ContentType.Text.Html)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            webSocket("/ws/{playerId}") {
                val playerId = call.parameters["playerId"] ?: return@webSocket
                playerSessions[playerId] = this
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val msg = Json.decodeFromString<SocketMessage>(frame.readText())
                            val room = rooms.values.find { r -> r.players.any { it.id == playerId } } ?: continue
                            when (msg.type) {
                                "I_UNDERSTAND" -> if (room.phase == "PREPARING") { room.readyPlayers.add(playerId); if (room.readyPlayers.size == room.players.size) triggerNextPhase(room) }
                                "UPDATE_PROFILE" -> {
                                    val data = Json.decodeFromString<Map<String, String>>(msg.data); val p = room.players.find { it.id == playerId }
                                    if (p != null) { room.players[room.players.indexOf(p)] = p.copy(name = data["name"] ?: p.name, avatar = data["avatar"] ?: p.avatar); broadcastPlayerList(room) }
                                }
                                "TOGGLE_READY" -> if (room.phase == "LOBBY" && room.hostId != playerId) { val p = room.players.find { it.id == playerId }; if (p != null) { p.isReady = !p.isReady; broadcastPlayerList(room) } }
                                "WEREWOLF_VOTE" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p != null && p.moonCurse != 1 && p.role != Role.DERP_WOLF && (p.trulyTeam == "Sói" || p.trulyTeam == "cupid") && p.team == "Sói") {
                                        val d = room.players.find { it.id == msg.data }
                                        if (d != null && !d.isDead && !((d.trulyTeam == "Sói" || d.trulyTeam == "cupid") && d.team == "Sói")) { d.werewolfMark += 1; broadcastPlayerList(room) }
                                    }
                                }
                                "CUPID_SELECT" -> {
                                    val ids = Json.decodeFromString<List<String>>(msg.data); val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.CUPID && p.moonCurse != 1 && ids.size == 2) { room.players.forEach { if (ids.contains(it.id)) { it.linked = 2; it.trulyTeam = "cupid" } }; p.trulyTeam = "cupid"; broadcastPlayerList(room) }
                                }
                                "CURSER_ACTIVATE" -> { val p = room.players.find { it.id == playerId }; if (p?.role == Role.CURSER_WEREWOLF && p.moonCurse != 1 && !room.curserUsedAbility) { room.isCurseActiveThisNight = true; room.curserUsedAbility = true } }
                                "SEER_CHECK" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.SEER && p.moonCurse != 1) { val d = room.players.find { it.id == msg.data }; if (d != null) { val res = if (room.elderIsDead) "Dân" else d.team; launch { playerSessions[playerId]?.sendSerialized(SocketMessage("SEER_RESULT", res)) } } }
                                }
                                "PROPHET_CHECK" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.PROPHET_WEREWOLF && p.moonCurse != 1 && room.prophetUses > 0) { val d = room.players.find { it.id == msg.data }; if (d != null && !d.isDead) { room.prophetUses -= 1; broadcastAnnouncement(room, "Sói Tiên Tri đã soi ra ${d.role?.name}!") } }
                                }
                                "PROPHET_SKIP" -> { if (room.players.find { it.id == playerId }?.role == Role.PROPHET_WEREWOLF) broadcastAnnouncement(room, "Sói Tiên Tri đã soi ra Dân Làng.") }
                                "WITCH_SAVE" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.WITCH && p.moonCurse != 1 && !room.witchSaveUsed && room.tempDeadIds.contains(msg.data)) { if (!room.elderIsDead) { val d = room.players.find { it.id == msg.data }; d?.heal = 1; d?.shield = 0; room.tempDeadIds.remove(msg.data) }; room.witchSaveUsed = true; broadcastPlayerList(room) }
                                }
                                "WITCH_KILL" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.WITCH && p.moonCurse != 1 && !room.witchKillUsed) { val d = room.players.find { it.id == msg.data }; if (d != null && !d.isDead) { d.heal = 0; room.tempDeadIds.add(msg.data); room.witchKillUsed = true; broadcastPlayerList(room) } }
                                }
                                "GUARDIAN_PROTECT" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.GUARDIAN && p.moonCurse != 1 && !p.isDead) { val d = room.players.find { it.id == msg.data }; if (d != null && !d.isDead && d.name != p.count) { if (!room.elderIsDead) d.shield += 1; p.count = d.name; broadcastPlayerList(room) } }
                                }
                                "MOON_MAIDEN_SELECT" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.MOON_MAIDEN && p.moonCurse != 1 && !p.isDead) { val d = room.players.find { it.id == msg.data }; if (d != null && !d.isDead && d.name != p.count) { if (!room.elderIsDead) d.moonCurse = 1; p.count = d.name; broadcastPlayerList(room) } }
                                }
                                "PIPER_CHARM" -> {
                                    val p = room.players.find { it.id == playerId }; val ids = Json.decodeFromString<List<String>>(msg.data)
                                    if (p?.role == Role.PIPER && p.moonCurse != 1 && ids.size == 2) { ids.forEach { room.charmedPlayerIds.add(it) }; room.charmedPlayerIds.add(playerId); broadcastPlayerList(room) }
                                }
                                "HUNTER_KILL" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.HUNTER && p.moonCurse != 1 && p.hunterBullets > 0 && !p.isDead) { val d = room.players.find { it.id == msg.data }; if (d != null && !d.isDead) { p.hunterBullets -= 1; d.heal -= 1; if (d.trulyTeam == "Dân" || (d.trulyTeam == "cupid" && d.team == "Dân") || d.trulyTeam == "piper") p.heal = 0; broadcastPlayerList(room) } }
                                }
                                "HUNTER_REVENGE_SELECT" -> {
                                    val p = room.players.find { it.id == playerId }
                                    if (p?.role == Role.HUNTER && p.canHunterPassive && p.heal <= 0) { val d = room.players.find { it.id == msg.data }; if (d != null && !d.isDead) { d.heal = 0; p.canHunterPassive = false; broadcastPlayerList(room); triggerNextPhase(room) } }
                                }
                                "DEV_COMMAND" -> handleDevCommand(room, msg.data, playerId)
                                "SKIP_DEFENSE" -> if (room.phase == "TRIAL_DEFENSE" && room.trialTargetId == playerId) triggerNextPhase(room)
                                "DERP_REVENGE_KILL" -> if (room.derpWolfRevengeList.contains(msg.data)) { room.players.find { it.id == msg.data }?.heal = 0; room.derpWolfRevengeList.clear(); broadcastPlayerList(room) }
                                "KICK_PLAYER" -> { // Host kick người chơi
                                    if (room.hostId == playerId && room.phase == "LOBBY") {
                                        val targetId = msg.data
                                        if (targetId != room.hostId) {
                                            room.players.removeIf { it.id == targetId }
                                            launch { playerSessions[targetId]?.sendSerialized(SocketMessage("KICKED", "Bạn đã bị mời ra khỏi phòng!")) }
                                            broadcastPlayerList(room)
                                        }
                                    }
                                }
                                "SWAP_PLAYERS" -> {
                                    if (room.hostId == playerId && room.phase == "LOBBY") {
                                        val parts = msg.data.split("|")
                                        if (parts.size == 2) {
                                            val i = parts[0].toIntOrNull() ?: -1
                                            val j = parts[1].toIntOrNull() ?: -1
                                            if (i in room.players.indices && j in room.players.indices) {
                                                val temp = room.players[i]
                                                room.players[i] = room.players[j]
                                                room.players[j] = temp
                                                broadcastPlayerList(room)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally { playerSessions.remove(playerId) }
            }
            post("/create-room") { 
                val host = call.receive<Player>(); 
                val code = (100000..999999).random().toString(); 
                val newPlayer = host.copy(isReady = true, isHost = true)
                rooms[code] = Room(code, host.id, mutableListOf(newPlayer)); 
                call.respond(rooms[code]!!) 
            }
            post("/join-room/{code}") { 
                val code = call.parameters["code"] ?: ""; val player = call.receive<Player>(); val room = rooms[code]
                if (room != null) { 
                    if (room.players.none { it.id == player.id }) { 
                        room.players.add(player.copy(isReady = true, isHost = (player.id == room.hostId)))
                        broadcastPlayerList(room) 
                    }; 
                    call.respond(room) 
                } else call.respond(io.ktor.http.HttpStatusCode.NotFound) 
            }
            post("/leave-room/{code}") { 
                val code = call.parameters["code"] ?: ""; val id = call.receive<Map<String, String>>()["id"] ?: ""
                val room = rooms[code]
                if (room != null) {
                    room.players.removeIf { it.id == id }
                    if (room.players.isEmpty() || room.players.none { !it.id.startsWith("bot_") }) {
                        rooms.remove(code)
                    } else {
                        if (room.hostId == id) {
                            val nextHost = room.players.find { !it.id.startsWith("bot_") }
                            if (nextHost != null) {
                                room.hostId = nextHost.id
                                room.players.forEach { it.isHost = (it.id == room.hostId) }
                            }
                        }
                        broadcastPlayerList(room)
                    }
                    call.respond(mapOf("ok" to true))
                } else call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }
            get("/room/{code}/distribute") {
                val code = call.parameters["code"] ?: ""; val ratio = (call.parameters["ratio"] ?: "0.25").toDouble(); val room = rooms[code] ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound)
                if (room.players.size < 8 || room.players.any { !it.isReady }) return@get call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Lỗi!")
                room.wolfRatio = ratio; room.prophetUses = calculateProphetUses(room.players.size, ratio)
                room.assignments.putAll(moderator.distributeRoles(room.players, ratio)); room.readyPlayers.clear(); room.phase = "PREPARING"
                room.assignments.forEach { (pid, assign) -> GlobalScope.launch { playerSessions[pid]?.sendSerialized(SocketMessage("YOUR_ROLE", Json.encodeToString(assign))) } }
                room.players.forEach { p -> GlobalScope.launch { playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "PREPARING|60")) } }
                call.respond(mapOf("ok" to true))
            }
            post("/room/{code}/next-phase") { val room = rooms[call.parameters["code"]] ?: return@post call.respond(io.ktor.http.HttpStatusCode.NotFound); triggerNextPhase(room); call.respond(mapOf("ok" to true)) }
            get("/room/{code}/players") { 
                val room = rooms[call.parameters["code"]]
                room?.players?.forEach { it.isHost = (it.id == room.hostId) }
                call.respond(room?.players ?: emptyList<Player>()) 
            }
        }
    }.start(wait = true)
}

fun triggerNextPhase(room: Room) {
    room.nightJob?.cancel()
    GlobalScope.launch {
        try {
            // Tạo khoảng trễ 3 giây để server xử lý và tạo hiệu ứng kịch tính cho người chơi
            if (room.phase != "LOBBY" && room.phase != "PREPARING") {
                delay(3000)
            }

            when (room.phase) {
                "LOBBY" -> { room.phase = "PREPARING"; room.dayCount = 1 }
                "PREPARING" -> { 
                    room.phase = "NIGHT"
                    room.nightActionList = getNightOrder(room)
                    room.currentNightActionIndex = 0
                    if (room.nightActionList.isEmpty()) { processGameLogic(room); room.phase = "DAY" }
                }
                "NIGHT" -> { 
                    if (room.currentNightActionIndex < room.nightActionList.size - 1) room.currentNightActionIndex++ 
                    else { processGameLogic(room); room.phase = "DAY" } 
                }
                "DAY" -> {
                    val alive = room.players.filter { !it.isDead }; val max = if (alive.isNotEmpty()) alive.maxOf { it.vote } else 0
                    val top = alive.filter { it.vote == max && max > 0 }
                    if (top.size == 1) { room.phase = "TRIAL_DEFENSE"; room.trialTargetId = top[0].id } 
                    else { 
                        processGameLogic(room); room.phase = "NIGHT"; room.dayCount++; room.nightActionList = getNightOrder(room); room.currentNightActionIndex = 0 
                        if (room.nightActionList.isEmpty()) { processGameLogic(room); room.phase = "DAY" }
                    }
                }
                "TRIAL_DEFENSE" -> room.phase = "TRIAL_VOTING"
                "TRIAL_VOTING" -> { 
                    processGameLogic(room)
                    if (room.phase != "HUNTER_REVENGE") { 
                        room.phase = "NIGHT"; room.dayCount++; room.nightActionList = getNightOrder(room); room.currentNightActionIndex = 0; room.trialTargetId = null 
                        if (room.nightActionList.isEmpty()) { processGameLogic(room); room.phase = "DAY" }
                    } 
                }
                "HUNTER_REVENGE" -> { 
                    val alive = room.players.filter { !it.isDead }; if (alive.isNotEmpty()) { val target = alive.random(); target.heal = 0 }
                    processGameLogic(room); room.phase = "NIGHT"; room.dayCount++; room.nightActionList = getNightOrder(room); room.currentNightActionIndex = 0; room.trialTargetId = null
                    if (room.nightActionList.isEmpty()) { processGameLogic(room); room.phase = "DAY" }
                }
                else -> room.phase = "NIGHT"
            }

            val duration = if (room.phase == "NIGHT" && room.nightActionList.isNotEmpty()) getNightActionDuration(room, room.nightActionList[room.currentNightActionIndex]) else getPhaseDuration(room)
            if (room.phase != "LOBBY" && room.tickSpeed > 0) {
                room.nightJob = launch {
                    delay((duration * 1000L) / room.tickSpeed)
                    triggerNextPhase(room)
                }
            }
            room.players.forEach { p -> launch { 
                val cur = if (room.phase == "NIGHT" && room.nightActionList.isNotEmpty()) room.nightActionList[room.currentNightActionIndex] else room.phase
                playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "$cur|$duration|${room.russianStatus ?: ""}|${room.dayCount}")) 
            } }
            broadcastPlayerList(room)
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
        }
    }
}

fun getNightOrder(room: Room): MutableList<String> {
    val full = listOf(//"THIEF",
        "THREE_BROTHERS", "TWINS", "CUPID", "MOON_MAIDEN", "GUARDIAN", "WEREWOLF", "CURSER_WEREWOLF", "PROPHET_WEREWOLF", "SEER", "CELESTIAL_FOX", "WITCH", "PIPER", "ELDER")
    val roles = room.players.mapNotNull { it.role?.name }.toSet(); val order = full.filter { roles.contains(it) }.toMutableList()
    if (room.dayCount > 0) { //order.remove("THIEF");
        order.remove("THREE_BROTHERS"); order.remove("CUPID") }
    return order
}

fun processGameLogic(room: Room) {
    room.players.forEach { if (it.heal <= 0 && !it.isDead) room.tempDeadIds.add(it.id) else if (it.heal >= 1) room.tempDeadIds.remove(it.id) }
    if (!room.elderIsDead) { val elder = room.players.find { it.role == Role.ELDER }; if (elder != null && elder.heal <= 0) room.elderIsDead = true }
    if (room.phase == "TRIAL_VOTING") { val t = room.players.find { it.id == room.trialTargetId }; if (t != null && !t.isDead && t.killVote > t.saveVote) { t.heal -= 1; if (t.role == Role.DERP_WOLF) { room.derpWolfRevengeList.clear(); room.derpWolfRevengeList.addAll(t.killersVotedForMe) } } }
    if (room.phase == "DAY" || room.phase == "TRIAL_VOTING") room.players.forEach { it.vote = 0; it.saveVote = 0; it.killVote = 0; it.killersVotedForMe.clear() }
    if (room.phase == "NIGHT") {
        val targets = room.players.filter { !it.isDead && !((it.trulyTeam == "Sói" || it.trulyTeam == "cupid") && it.team == "Sói") }
        if (targets.isNotEmpty()) { val max = targets.maxOf { it.werewolfMark }; if (max > 0) { val top = targets.filter { it.werewolfMark == max }; if (top.size == 1) { top[0].shield -= 1; if (room.isCurseActiveThisNight && top[0].role != Role.LYCAN && top[0].shield == -1) { top[0].trulyTeam = "Sói"; top[0].team = "Sói"; top[0].shield = 0 } } } }
        room.isCurseActiveThisNight = false
        val russian = room.players.find { it.role == Role.RUSSIAN && !it.isDead }
        if (russian != null) { val idx = room.players.indexOf(russian); val s = room.players.size; val l = if (idx == 0) s - 1 else idx - 1; val ri = if (idx == s - 1) 0 else idx + 1; room.russianStatus = if (listOf(room.players[l], room.players[ri]).any { it.team == "Sói" && !it.isDead }) "RUSSIAN_VODKA" else "RUSSIAN_CALM" } else room.russianStatus = null
    }
    room.players.forEach { p -> if (p.role == Role.LYCAN && p.shield == -1) { p.team = "Sói"; p.shield = 0 }; if (p.role != Role.LYCAN && p.shield < 0) { p.heal += p.shield; p.shield = 0 }; if (room.phase == "NIGHT") { if (p.role != Role.ELDER && p.shield > 0) p.shield = 0; p.vote = 0; p.werewolfMark = 0; p.moonCurse = 0; p.killersVotedForMe.clear() } }
    room.charmedPlayerIds.removeIf { id -> room.players.any { it.id == id && it.isDead } }
    val couple = room.players.filter { it.linked == 2 }
    if (couple.isNotEmpty() && couple.any { it.heal <= 0 }) couple.forEach { it.heal = 0; it.linked = 1 }
    room.players.forEach { if (it.heal <= 0) it.isDead = true }
    room.players.filter { it.isDead }.forEach { it.shield = 0; it.vote = 0; it.saveVote = 0; it.killVote = 0; it.werewolfMark = 0; if (it.linked == -1) it.linked = 1 }
    val aliveCount = room.players.count { !it.isDead }; val isCoupleAlive = room.players.count { it.linked == 2 && !it.isDead } == 2
    if (aliveCount == 4 && isCoupleAlive) room.winner = "CUPID"
    val charmed = room.charmedPlayerIds.size; if (charmed == aliveCount && aliveCount > 0) room.winner = "PIPER"
    if (room.winner == null) {
        val wolves = room.players.filter { !it.isDead && it.trulyTeam == "Sói" }
        val others = room.players.filter { !it.isDead && it.trulyTeam != "Sói" }
        if (wolves.size >= others.size) room.winner = "WEREWOLF_TEAM"
        else if (wolves.none { it.role == Role.WEREWOLF || it.role == Role.CURSER_WEREWOLF || it.role == Role.PROPHET_WEREWOLF }) room.winner = "VILLAGER_TEAM"
    }
    if (room.phase == "NIGHT" || room.phase == "DAY" || room.phase == "TRIAL_VOTING") room.tempDeadIds.clear()
    val deadHunter = room.players.find { it.role == Role.HUNTER && it.isDead && it.canHunterPassive }
    if (deadHunter != null && room.winner == null) room.phase = "HUNTER_REVENGE"
}

suspend fun broadcastPlayerList(room: Room) {
    room.players.forEach { it.isHost = (it.id == room.hostId) }
    val json = Json.encodeToString(room.players)
    room.players.forEach { p -> playerSessions[p.id]?.sendSerialized(SocketMessage("PLAYER_LIST_UPDATE", json)) }
}

suspend fun broadcastAnnouncement(room: Room, text: String) {
    room.players.forEach { p -> playerSessions[p.id]?.sendSerialized(SocketMessage("ANNOUNCEMENT", text)) }
}

fun handleDevCommand(room: Room, cmd: String, devId: String) {
    val parts = cmd.split(" ")
    val action = parts[0].lowercase()
    val moderator = Moderator()

    fun resolveTargets(selector: String): List<Player> {
        if (!selector.startsWith("@")) return emptyList()
        val s = selector.substring(1).lowercase()
        return when {
            s == "a" || s == "all" -> room.players
            s == "v" || s == "villagers" -> room.players.filter { it.trulyTeam == "Dân" }
            s == "w" || s == "wolf" || s == "wolves" -> room.players.filter { it.trulyTeam == "Sói" }
            s == "c" || s == "cupid" -> room.players.filter { it.trulyTeam == "cupid" }
            else -> room.players.filter { it.name.contains(s, true) }
        }
    }

    GlobalScope.launch {
        when (action) {
            "/next" -> {
                if (parts.size >= 2 && parts[1].lowercase() == "day") {
                    processGameLogic(room)
                    room.phase = "DAY"
                    room.dayCount++
                    triggerNextPhase(room)
                } else if (parts.size >= 2 && parts[1].lowercase() == "night") {
                    processGameLogic(room)
                    room.phase = "NIGHT"
                    room.dayCount++
                    room.nightActionList = getNightOrder(room)
                    room.currentNightActionIndex = 0
                    triggerNextPhase(room)
                } else if (parts.size >= 2 && parts[1].lowercase() == "execution") {
                    val target = room.players.filter { !it.isDead }.maxByOrNull { it.vote } ?: room.players.find { !it.isDead }
                    if (target != null) {
                        room.phase = "TRIAL_DEFENSE"
                        room.trialTargetId = target.id
                        triggerNextPhase(room)
                    }
                } else if (parts.size >= 2 && parts[1].lowercase() == "phase") {
                    if (room.phase == "NIGHT") {
                        processGameLogic(room)
                        room.phase = "DAY"
                    } else {
                        room.phase = "NIGHT"
                        room.dayCount++
                        room.nightActionList = getNightOrder(room)
                        room.currentNightActionIndex = 0
                    }
                    triggerNextPhase(room)
                } else {
                    triggerNextPhase(room)
                }
            }
            "/start" -> { 
                if (room.phase == "LOBBY") {
                    room.players.forEach { it.isReady = true }
                    // Gọi đúng logic chia bài chuẩn của game
                    room.wolfRatio = 0.25
                    room.prophetUses = calculateProphetUses(room.players.size, 0.25)
                    room.assignments.putAll(moderator.distributeRoles(room.players, 0.25))
                    room.readyPlayers.clear()
                    room.phase = "PREPARING"
                    room.dayCount = 1
                    room.assignments.forEach { (pid, assign) -> 
                        launch { playerSessions[pid]?.sendSerialized(SocketMessage("YOUR_ROLE", Json.encodeToString(assign))) }
                    }
                    room.players.forEach { p -> 
                        launch { playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "PREPARING|60||${room.dayCount}")) }
                    }
                    broadcastPlayerList(room)
                }
            }
            "/end" -> {
                room.players.forEach { p ->
                    launch { playerSessions[p.id]?.sendSerialized(SocketMessage("KICKED", "Phòng đã bị giải tán bởi Dev!")) }
                }
                rooms.remove(room.code)
            }
            "/setrole" -> {
                if (parts.size >= 3) {
                    val targets = resolveTargets(parts[1])
                    val rolePart = if (parts[2].startsWith("\\")) parts[2].substring(1) else parts[2]
                    val r = try { Role.valueOf(rolePart.uppercase()) } catch (e: Exception) { null }
                    if (targets.isNotEmpty() && r != null) { 
                        targets.forEach { p ->
                            p.role = r
                            if (r.type == RoleType.WEREWOLF) { p.trulyTeam = "Sói"; p.team = "Sói" }
                            else if (r == Role.LYCAN) { p.trulyTeam = "Sói"; p.team = "Dân" }
                            else if (r == Role.PIPER) { p.trulyTeam = "piper"; p.team = "Dân" }
                            else { p.trulyTeam = "Dân"; p.team = "Dân" }
                        }
                        broadcastPlayerList(room) 
                    }
                }
            }
            "/kill" -> {
                if (parts.size >= 2) {
                    val targets = resolveTargets(parts[1])
                    if (targets.isNotEmpty()) {
                        targets.forEach { it.heal = 0 }
                        processGameLogic(room)
                        broadcastPlayerList(room)
                    }
                }
            }
            "/shield" -> {
                if (parts.size >= 2) {
                    val targets = resolveTargets(parts[1])
                    targets.forEach { it.shield += 1 }
                    broadcastPlayerList(room)
                }
            }
            "/curse" -> {
                if (parts.size >= 2) {
                    val targets = resolveTargets(parts[1])
                    targets.forEach { it.moonCurse = 1 }
                    broadcastPlayerList(room)
                }
            }
            "/time" -> {
                if (parts.size >= 3 && parts[1] == "set") {
                    when (parts[2].lowercase()) {
                        "day" -> {
                            processGameLogic(room)
                            room.phase = "DAY"
                            room.dayCount++
                            triggerNextPhase(room)
                        }
                        "night" -> {
                            room.phase = "NIGHT"
                            room.nightActionList = getNightOrder(room)
                            room.currentNightActionIndex = 0
                            triggerNextPhase(room)
                        }
                    }
                }
            }
            "/tick" -> {
                if (parts.size >= 3 && parts[1] == "set") {
                    room.tickSpeed = parts[2].toIntOrNull() ?: 1
                    triggerNextPhase(room)
                }
            }
        }
    }
}
