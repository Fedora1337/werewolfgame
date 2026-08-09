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
import kotlinx.coroutines.launch

@Serializable
data class Player(
    val id: String, 
    val name: String, 
    val avatar: String,
    var isReady: Boolean = false,
    var role: Role? = null,
    var trulyTeam: String = "Dân",
    var team: String = "Dân",
    var heal: Int = 1,
    var shield: Int = 0,
    var linked: Int = 0,
    var isDead: Boolean = false,
    var vote: Int = 0,
    var saveVote: Int = 0,
    var killVote: Int = 0,
    var werewolfMark: Int = 0,
    val killersVotedForMe: MutableList<String> = mutableListOf()
)

@Serializable
data class GameAssignment(val playerName: String, val role: String, val description: String)

@Serializable
data class Room(
    val code: String,
    val hostId: String,
    val players: MutableList<Player> = mutableListOf(),
    val assignments: MutableMap<String, GameAssignment> = mutableMapOf(),
    val readyPlayers: MutableSet<String> = mutableSetOf(),
    var phase: String = "LOBBY",
    var dayCount: Int = 0, // Theo dõi số ngày đã trôi qua
    val derpWolfRevengeList: MutableList<String> = mutableListOf(),
    var trialTargetId: String? = null
)

@Serializable
data class SocketMessage(val type: String, val data: String)

val rooms = ConcurrentHashMap<String, Room>()
val playerSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

enum class RoleType { WEREWOLF, VILLAGER, SPECIAL }

enum class Role(val type: RoleType, val description: String) {
    WEREWOLF(RoleType.WEREWOLF, "Ma Sói - Ăn thịt dân làng mỗi đêm."),
    DERP_WOLF(RoleType.WEREWOLF, "Sói Ngu - Không tham gia tiệc sói, có quyền báo thù nếu bị treo cổ."),
    LYCAN(RoleType.SPECIAL, "Bán Sói - Ban đầu là dân, nếu bị sói cắn sẽ hóa sói."),
    VILLAGER(RoleType.VILLAGER, "Dân Làng - Không có chức năng đặc biệt."),
    SEER(RoleType.SPECIAL, "Tiên Tri - Mỗi đêm soi phe 1 người (Dân/Sói)."),
    GUARDIAN(RoleType.SPECIAL, "Bảo Vệ - Bảo vệ 1 người khỏi Sói mỗi đêm."),
    CURSER_WEREWOLF(RoleType.WEREWOLF, "Sói Nguyền - Biến nạn nhân bị sói cắn thành sói (1 lần)."),
    PROPHET_WEREWOLF(RoleType.WEREWOLF, "Sói Tiên Tri - Soi chức năng 1 người cho cả làng biết."),
    CELESTIAL_FOX(RoleType.SPECIAL, "Hồ Ly - Soi phe 1 người + 2 hàng xóm."),
    MOON_MAIDEN(RoleType.SPECIAL, "Nguyệt Nữ - Khóa chức năng 1 người mỗi đêm."),
    TWINS(RoleType.SPECIAL, "Hai Chị Em - Đi theo cặp."),
    ELDER(RoleType.SPECIAL, "Già Làng - 1 mạng trước Sói, khi chết dân làng mất năng lực."),
    WITCH(RoleType.SPECIAL, "Phù Thủy - Có bình thuốc độc và thuốc cứu."),
    CUPID(RoleType.SPECIAL, "Thần Tình Yêu - Ghép đôi 2 người."),
    HUNTER(RoleType.SPECIAL, "Thợ Săn - Kéo theo 1 người khi chết hoặc bắn vào ban đêm.")
}

class Moderator {
    fun distributeRoles(players: List<Player>, wolfRatio: Double): Map<String, GameAssignment> {
        val playerCount = players.size
        val roleDeck = mutableListOf<Role>()
        val werewolfCount = (playerCount * wolfRatio).toInt()
        val villagerCount = werewolfCount
        val specialCount = playerCount - werewolfCount - villagerCount

        repeat(werewolfCount) { roleDeck.add(Role.WEREWOLF) }
        repeat(villagerCount) { roleDeck.add(Role.VILLAGER) }

        val availableSpecialRoles = Role.values().filter { it.type == RoleType.SPECIAL }.shuffled().toMutableList()
        for (i in 0 until specialCount) {
            if (availableSpecialRoles.isNotEmpty()) roleDeck.add(availableSpecialRoles.removeAt(0))
            else roleDeck.add(Role.VILLAGER)
        }
        roleDeck.shuffle()

        return players.zip(roleDeck).associate { (player, role) ->
            player.role = role
            player.shield = if (role == Role.ELDER) 1 else 0
            if (role.type == RoleType.WEREWOLF) {
                player.trulyTeam = "Sói"
                player.team = "Sói"
            } else if (role == Role.LYCAN) {
                player.trulyTeam = "Sói"
                player.team = "Dân"
            } else {
                player.trulyTeam = "Dân"
                player.team = "Dân"
            }
            player.id to GameAssignment(player.name, role.name, role.description)
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
        }

        val moderator = Moderator()

        routing {
            staticResources("/", "static")

            webSocket("/ws/{playerId}") {
                val playerId = call.parameters["playerId"] ?: return@webSocket
                playerSessions[playerId] = this
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val msg = Json.decodeFromString<SocketMessage>(frame.readText())
                            val room = rooms.values.find { r -> r.players.any { it.id == playerId } } ?: continue
                            
                            when (msg.type) {
                                "I_UNDERSTAND" -> {
                                    if (room.phase == "PREPARING") {
                                        room.readyPlayers.add(playerId)
                                        if (room.readyPlayers.size == room.players.size) {
                                            room.phase = "NIGHT"
                                            room.players.forEach { p -> launch { playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "NIGHT")) } }
                                        }
                                    }
                                }
                                "UPDATE_PROFILE" -> {
                                    val data = Json.decodeFromString<Map<String, String>>(msg.data)
                                    val p = room.players.find { it.id == playerId }
                                    if (p != null) {
                                        room.players[room.players.indexOf(p)] = p.copy(name = data["name"] ?: p.name, avatar = data["avatar"] ?: p.avatar)
                                        broadcastPlayerList(room)
                                    }
                                }
                                "TOGGLE_READY" -> {
                                    if (room.phase == "LOBBY" && room.hostId != playerId) {
                                        val p = room.players.find { it.id == playerId }
                                        if (p != null) {
                                            p.isReady = !p.isReady
                                            broadcastPlayerList(room)
                                        }
                                    }
                                }
                                "WEREWOLF_VOTE" -> {
                                    val targetId = msg.data
                                    val me = room.players.find { it.id == playerId }
                                    // Chỉ Sói "xịn" (đã hóa hình, không ngu) mới được tham gia tiệc
                                    if (me != null && me.role != Role.DERP_WOLF && me.trulyTeam == "Sói" && me.team == "Sói") {
                                        val target = room.players.find { it.id == targetId }
                                        if (target != null && !target.isDead && !(target.trulyTeam == "Sói" && target.team == "Sói")) {
                                            target.werewolfMark += 1
                                            broadcastPlayerList(room)
                                        }
                                    }
                                }
                                "DERP_REVENGE_KILL" -> {
                                    val targetId = msg.data
                                    if (room.derpWolfRevengeList.contains(targetId)) {
                                        room.players.find { it.id == targetId }?.heal = 0
                                        room.derpWolfRevengeList.clear()
                                        broadcastPlayerList(room)
                                    }
                                }
                                "SKIP_DEFENSE" -> { // Người trên giàn chấp nhận cái kết
                                    if (room.phase == "TRIAL_DEFENSE" && room.trialTargetId == playerId) {
                                        room.phase = "TRIAL_VOTING"
                                        room.players.forEach { p -> launch { playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "TRIAL_VOTING")) } }
                                    }
                                }
                            }
                        }
                    }
                } finally { playerSessions.remove(playerId) }
            }

            post("/create-room") {
                val host = call.receive<Player>()
                val code = (100000..999999).random().toString()
                rooms[code] = Room(code, host.id, mutableListOf(host.copy(isReady = true)))
                call.respond(rooms[code]!!)
            }

            post("/join-room/{code}") {
                val code = call.parameters["code"] ?: ""
                val player = call.receive<Player>()
                val room = rooms[code]
                if (room != null) {
                    if (room.players.none { it.id == player.id }) {
                        room.players.add(player.copy(isReady = false))
                        broadcastPlayerList(room)
                    }
                    call.respond(room)
                } else call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }

            post("/leave-room/{code}") {
                val code = call.parameters["code"] ?: ""
                val id = call.receive<Map<String, String>>()["id"] ?: ""
                val room = rooms[code]
                if (room != null) {
                    room.players.removeIf { it.id == id }
                    if (room.players.isEmpty()) rooms.remove(code) else broadcastPlayerList(room)
                    call.respond(mapOf("ok" to true))
                } else call.respond(io.ktor.http.HttpStatusCode.NotFound)
            }

            get("/room/{code}/distribute") {
                val code = call.parameters["code"] ?: ""
                val room = rooms[code] ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound)
                if (room.players.size < 8 || room.players.any { !it.isReady }) return@get call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Chưa đủ người hoặc chưa sẵn sàng!")
                
                val assignments = moderator.distributeRoles(room.players, (call.parameters["ratio"] ?: "0.25").toDouble())
                room.assignments.putAll(assignments)
                room.readyPlayers.clear()
                room.phase = "PREPARING"
                assignments.forEach { (pid, assign) -> launch { playerSessions[pid]?.sendSerialized(SocketMessage("YOUR_ROLE", Json.encodeToString(assign))) } }
                room.players.forEach { p -> launch { playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "PREPARING")) } }
                call.respond(mapOf("ok" to true))
            }

            post("/room/{code}/next-phase") {
                val code = call.parameters["code"] ?: ""
                val room = rooms[code] ?: return@post call.respond(io.ktor.http.HttpStatusCode.NotFound)
                
                when (room.phase) {
                    "NIGHT" -> {
                        processGameLogic(room)
                        room.phase = "DAY"
                        room.dayCount += 1
                    }
                    "DAY" -> {
                        val alivePlayers = room.players.filter { !it.isDead }
                        val maxVotes = if (alivePlayers.isNotEmpty()) alivePlayers.maxOf { it.vote } else 0
                        val topVoted = alivePlayers.filter { it.vote == maxVotes && maxVotes > 0 }
                        
                        if (topVoted.size == 1) {
                            room.phase = "TRIAL_DEFENSE"
                            room.trialTargetId = topVoted[0].id
                        } else {
                            processGameLogic(room)
                            room.phase = "NIGHT"
                        }
                    }
                    "TRIAL_DEFENSE" -> room.phase = "TRIAL_VOTING"
                    "TRIAL_VOTING" -> {
                        processGameLogic(room)
                        room.phase = "NIGHT"
                        room.trialTargetId = null
                    }
                    else -> room.phase = "NIGHT"
                }

                val duration = getPhaseDuration(room)
                room.players.forEach { p -> 
                    launch { 
                        playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "${room.phase}|$duration")) 
                    } 
                }
                broadcastPlayerList(room)
                call.respond(mapOf("phase" to room.phase, "duration" to duration))
            }

            get("/room/{code}/players") { call.respond(rooms[call.parameters["code"]]?.players ?: emptyList<Player>()) }
        }
    }.start(wait = true)
}

fun getPhaseDuration(room: Room): Int {
    val count = room.players.size
    val isDay1 = room.dayCount <= 1
    val lagBuffer = 5 // +5s phòng mạng ngáo

    return when (room.phase) {
        "DAY" -> {
            val baseTime = when {
                count >= 46 -> if (isDay1) 900 else 420
                count >= 31 -> if (isDay1) 600 else 300
                count >= 21 -> if (isDay1) 420 else 240
                count >= 16 -> if (isDay1) 300 else 180
                count >= 12 -> if (isDay1) 210 else 150
                else -> if (isDay1) 120 else 90
            }
            baseTime + lagBuffer
        }
        "TRIAL_DEFENSE" -> {
            val baseTime = when {
                count >= 21 -> 60
                count >= 12 -> 45
                else -> 30
            }
            baseTime + lagBuffer
        }
        "TRIAL_VOTING" -> {
            val baseTime = when {
                count >= 46 -> 45
                count >= 31 -> 30
                count >= 21 -> 20
                count >= 12 -> 15
                else -> 10
            }
            baseTime + lagBuffer
        }
        "PREPARING" -> 300 // Mặc định cho giai đoạn chuẩn bị
        else -> 0
    }
}

fun processGameLogic(room: Room) {
    if (room.phase == "TRIAL_VOTING") {
        // 1. Xử lý kết quả treo cổ
        val target = room.players.find { it.id == room.trialTargetId }
        if (target != null && !target.isDead) {
            if (target.killVote > target.saveVote) {
                target.heal -= 1
                if (target.role == Role.DERP_WOLF) {
                    room.derpWolfRevengeList.clear()
                    room.derpWolfRevengeList.addAll(target.killersVotedForMe)
                }
            }
        }
    }

    if (room.phase == "DAY" || room.phase == "TRIAL_VOTING") {
        room.players.forEach {
            it.vote = 0
            it.saveVote = 0
            it.killVote = 0
            it.killersVotedForMe.clear()
        }
    }

    if (room.phase == "NIGHT") {
        val aliveTargets = room.players.filter { !it.isDead && !(it.trulyTeam == "Sói" && it.team == "Sói") }
        if (aliveTargets.isNotEmpty()) {
            val maxMarks = aliveTargets.maxOf { it.werewolfMark }
            if (maxMarks > 0) {
                val topMarked = aliveTargets.filter { it.werewolfMark == maxMarks }
                if (topMarked.size == 1) {
                    topMarked[0].shield -= 1
                }
            }
        }
    }

    room.players.forEach { p ->
        if (p.role == Role.LYCAN && p.shield == -1) {
            p.team = "Sói"
            p.shield = 0 
        }

        if (p.role != Role.LYCAN && p.shield < 0) {
            p.heal += p.shield
            p.shield = 0
        }

        if (room.phase == "NIGHT") {
            if (p.role != Role.ELDER && p.shield > 0) p.shield = 0
            p.vote = 0
            p.werewolfMark = 0
            p.killersVotedForMe.clear()
        }
    }

    val dyingLinked = room.players.filter { it.linked == 2 && it.heal <= 0 }
    if (dyingLinked.isNotEmpty()) {
        room.players.filter { it.linked == 2 }.forEach { p ->
            p.heal = 0
            p.linked = 1 
        }
    }

    room.players.forEach { p -> if (p.heal <= 0) p.isDead = true }
}

suspend fun broadcastPlayerList(room: Room) {
    val json = Json.encodeToString(room.players)
    room.players.forEach { p -> playerSessions[p.id]?.sendSerialized(SocketMessage("PLAYER_LIST_UPDATE", json)) }
}
