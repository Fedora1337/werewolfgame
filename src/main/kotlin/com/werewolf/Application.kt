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

@Serializable
data class Player(val id: String, val name: String, val avatar: String)

@Serializable
data class GameAssignment(val playerName: String, val role: String, val description: String)

@Serializable
data class Room(
    val code: String,
    val hostId: String,
    val players: MutableList<Player> = mutableListOf(),
    val assignments: MutableMap<String, GameAssignment> = mutableMapOf(),
    val readyPlayers: MutableSet<String> = mutableSetOf(), // Lưu ID những người đã bấm "Tôi đã hiểu"
    var phase: String = "LOBBY" // LOBBY, PREPARING, NIGHT, DAY
)

@Serializable
data class SocketMessage(val type: String, val data: String)

val rooms = ConcurrentHashMap<String, Room>()
val playerSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

enum class RoleType { WEREWOLF, VILLAGER, SPECIAL }

enum class Role(val type: RoleType, val description: String) {
    WEREWOLF(RoleType.WEREWOLF, "Ma Sói - Ăn thịt dân làng mỗi đêm"),
    VILLAGER(RoleType.VILLAGER, "Dân Làng - Tìm ra sói và treo cổ chúng"),
    SEER(RoleType.SPECIAL, "Tiên Tri - Soi xem ai là sói mỗi đêm"),
    DOCTOR(RoleType.SPECIAL, "Bảo Vệ - Chọn một người để bảo vệ mỗi đêm"),
    HUNTER(RoleType.SPECIAL, "Thợ Săn - Khi chết được bắn một người theo"),
    WITCH(RoleType.SPECIAL, "Phù Thủy - Có 1 bình thuốc cứu và 1 bình thuốc độc"),
    CUPID(RoleType.SPECIAL, "Thần Tình Yêu - Ghép đôi 2 người thành cặp đôi định mệnh")
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

        val availableSpecialRoles = Role.values()
            .filter { it.type == RoleType.SPECIAL }
            .shuffled()
            .toMutableList()

        for (i in 0 until specialCount) {
            if (availableSpecialRoles.isNotEmpty()) {
                roleDeck.add(availableSpecialRoles.removeAt(0))
            } else {
                roleDeck.add(Role.VILLAGER)
            }
        }
        roleDeck.shuffle()

        return players.zip(roleDeck).associate { (player, role) ->
            player.id to GameAssignment(player.name, role.name, role.description)
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
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
                            val text = frame.readText()
                            val msg = Json.decodeFromString<SocketMessage>(text)
                            when (msg.type) {
                                "I_UNDERSTAND" -> {
                                    val room = rooms.values.find { r -> r.players.any { it.id == playerId } }
                                    if (room != null && room.phase == "PREPARING") {
                                        room.readyPlayers.add(playerId)
                                        if (room.readyPlayers.size == room.players.size) {
                                            room.phase = "NIGHT"
                                            room.players.forEach { p ->
                                                playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "NIGHT"))
                                            }
                                        }
                                    }
                                }
                                "UPDATE_PROFILE" -> {
                                    val updateData = Json.decodeFromString<Map<String, String>>(msg.data)
                                    val room = rooms.values.find { r -> r.players.any { it.id == playerId } }
                                    if (room != null) {
                                        val index = room.players.indexOfFirst { it.id == playerId }
                                        if (index != -1) {
                                            val oldPlayer = room.players[index]
                                            room.players[index] = oldPlayer.copy(
                                                name = updateData["name"] ?: oldPlayer.name,
                                                avatar = updateData["avatar"] ?: oldPlayer.avatar
                                            )
                                            // Gửi danh sách cập nhật cho mọi người trong phòng
                                            room.players.forEach { p ->
                                                playerSessions[p.id]?.sendSerialized(SocketMessage("PLAYER_LIST_UPDATE", Json.encodeToString(room.players)))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    playerSessions.remove(playerId)
                }
            }

            post("/create-room") {
                val host = call.receive<Player>()
                val code = (100000..999999).random().toString()
                val newRoom = Room(code, host.id, mutableListOf(host))
                rooms[code] = newRoom
                call.respond(newRoom)
            }

            post("/join-room/{code}") {
                val code = call.parameters["code"] ?: ""
                val player = call.receive<Player>()
                val room = rooms[code]
                if (room != null) {
                    if (room.players.none { it.id == player.id }) {
                        room.players.add(player)
                        room.players.forEach { p ->
                            playerSessions[p.id]?.sendSerialized(SocketMessage("PLAYER_LIST_UPDATE", Json.encodeToString(room.players)))
                        }
                    }
                    call.respond(room)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound, "Phòng không tồn tại!")
                }
            }

            get("/room/{code}/distribute") {
                val code = call.parameters["code"] ?: ""
                val ratioParam = call.parameters["ratio"] ?: "0.25"
                val wolfRatio = ratioParam.toDouble()
                val room = rooms[code]
                
                if (room == null) {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound, "Không tìm thấy phòng!")
                    return@get
                }

                // Kiểm tra số lượng người chơi (tối thiểu 8)
                if (room.players.size < 8) {
                    call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Cần tối thiểu 8 người để bắt đầu game!")
                    return@get
                }

                val assignments = moderator.distributeRoles(room.players, wolfRatio)
                room.assignments.putAll(assignments)
                room.readyPlayers.clear()
                room.phase = "PREPARING" 

                assignments.forEach { (playerId, assignment) ->
                    playerSessions[playerId]?.sendSerialized(SocketMessage("YOUR_ROLE", Json.encodeToString(assignment)))
                }
                
                room.players.forEach { p ->
                    playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", "PREPARING"))
                }
                
                call.respond(mapOf("status" to "started"))
            }

            post("/room/{code}/next-phase") {
                val code = call.parameters["code"] ?: ""
                val room = rooms[code] ?: return@post call.respond(io.ktor.http.HttpStatusCode.NotFound)
                
                room.phase = if (room.phase == "NIGHT") "DAY" else "NIGHT"
                
                room.players.forEach { p ->
                    playerSessions[p.id]?.sendSerialized(SocketMessage("PHASE_UPDATE", room.phase))
                }
                call.respond(mapOf("newPhase" to room.phase))
            }

            get("/room/{code}/players") {
                val code = call.parameters["code"] ?: ""
                val room = rooms[code]
                call.respond(room?.players ?: emptyList())
            }
        }
    }.start(wait = true)
}
