package com.chattriggers.ctjs.internal.listeners

import com.chattriggers.ctjs.api.entity.BlockEntity
import com.chattriggers.ctjs.api.entity.Entity
import com.chattriggers.ctjs.api.inventory.Item
import com.chattriggers.ctjs.api.message.TextComponent
import com.chattriggers.ctjs.api.render.Renderer
import com.chattriggers.ctjs.api.triggers.CancellableEvent
import com.chattriggers.ctjs.api.triggers.ChatTrigger
import com.chattriggers.ctjs.api.triggers.TriggerType
import com.chattriggers.ctjs.api.world.Scoreboard
import com.chattriggers.ctjs.api.world.World
import com.chattriggers.ctjs.api.world.block.BlockFace
import com.chattriggers.ctjs.api.world.block.BlockPos
import com.chattriggers.ctjs.engine.printToConsole
import com.chattriggers.ctjs.internal.console.LogType
import com.chattriggers.ctjs.internal.engine.CTEvents
import com.chattriggers.ctjs.internal.engine.JSContextFactory
import com.chattriggers.ctjs.internal.engine.JSLoader
import com.chattriggers.ctjs.internal.utils.Initializer
import com.chattriggers.ctjs.internal.utils.toMatrixStack
import gg.essential.universal.UMatrixStack
import gg.essential.universal.UMinecraft
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.event.player.*
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.TypedActionResult
import org.lwjgl.glfw.GLFW
import org.mozilla.javascript.Context

object ClientListener : Initializer {
    private var ticksPassed: Int = 0
    val chatHistory = mutableListOf<TextComponent>()
    val actionBarHistory = mutableListOf<TextComponent>()
    private val tasks = mutableListOf<Task>()
    private lateinit var packetContext: Context

    class Task(var delay: Int, val callback: () -> Unit)

    override fun init() {
        packetContext = JSContextFactory.enterContext()
        Context.exit()

        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ ->
            handleChatMessage(message, actionBar = false)
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            handleChatMessage(message, actionBar = overlay)
        }

        ClientTickEvents.START_CLIENT_TICK.register {
            synchronized(tasks) {
                tasks.removeAll {
                    if (it.delay-- <= 0) {
                        UMinecraft.getMinecraft().submit(it.callback)
                        true
                    } else false
                }
            }

            if (World.isLoaded()) {
                TriggerType.TICK.triggerAll(ticksPassed)
                ticksPassed++

                Scoreboard.resetCache()
            }
        }

        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            val event = CancellableEvent()
            TriggerType.MESSAGE_SENT.triggerAll(message, event)

            !event.isCancelled()
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { message ->
            val event = CancellableEvent()
            TriggerType.MESSAGE_SENT.triggerAll("/$message", event)

            !event.isCancelled()
        }

        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            // TODO: Why does Renderer.drawString not work in here?
            ScreenEvents.beforeRender(screen).register { _, stack, mouseX, mouseY, partialTicks ->
                renderTrigger(stack.toMatrixStack(), partialTicks) {
                    TriggerType.GUI_RENDER.triggerAll(mouseX, mouseY, screen)
                }
            }

            // TODO: Why does Renderer.drawString not work in here?
            ScreenEvents.afterRender(screen).register { _, stack, mouseX, mouseY, partialTicks ->
                renderTrigger(stack.toMatrixStack(), partialTicks) {
                    TriggerType.POST_GUI_RENDER.triggerAll(mouseX, mouseY, screen, partialTicks)
                }
            }

            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key, scancode, _ ->
                val event = CancellableEvent()
                TriggerType.GUI_KEY.triggerAll(GLFW.glfwGetKeyName(key, scancode), key, screen, event)
                !event.isCancelled()
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.remove(screen).register {
                TriggerType.GUI_CLOSED.triggerAll(screen)
            }
        }

        CTEvents.PACKET_RECEIVED.register { packet, ctx ->
            JSLoader.wrapInContext(packetContext) {
                TriggerType.PACKET_RECEIVED.triggerAll(packet, ctx)
            }
        }

        CTEvents.RENDER_TICK.register {
            TriggerType.STEP.triggerAll()
        }

        CTEvents.RENDER_OVERLAY.register { stack, partialTicks ->
            renderTrigger(stack, partialTicks) {
                TriggerType.RENDER_OVERLAY.triggerAll()
            }
        }

        CTEvents.RENDER_ENTITY.register { stack, entity, partialTicks, ci ->
            renderTrigger(stack, partialTicks) {
                TriggerType.RENDER_ENTITY.triggerAll(Entity.fromMC(entity), partialTicks, ci)
            }
        }

        CTEvents.RENDER_BLOCK_ENTITY.register { stack, blockEntity, partialTicks, ci ->
            renderTrigger(stack, partialTicks) {
                TriggerType.RENDER_BLOCK_ENTITY.triggerAll(BlockEntity(blockEntity), partialTicks, ci)
            }
        }

        AttackBlockCallback.EVENT.register { player, _, _, pos, direction ->
            if (!player.world.isClient) return@register ActionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.AttackBlock,
                World.getBlockAt(BlockPos(pos)).withFace(BlockFace.fromMC(direction)),
                event,
            )

            if (event.isCancelled()) ActionResult.FAIL else ActionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, _, _, entity, _ ->
            if (!player.world.isClient) return@register ActionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.AttackEntity,
                Entity.fromMC(entity),
                event,
            )

            if (event.isCancelled()) ActionResult.FAIL else ActionResult.PASS
        }

        CTEvents.BREAK_BLOCK.register { pos ->
            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.BreakBlock,
                World.getBlockAt(BlockPos(pos)),
                CancellableEvent(),
            )
        }

        UseBlockCallback.EVENT.register { player, _, hand, hitResult ->
            if (!player.world.isClient) return@register ActionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.UseBlock(hand),
                World.getBlockAt(BlockPos(hitResult.blockPos)).withFace(BlockFace.fromMC(hitResult.side)),
                event,
            )

            if (event.isCancelled()) ActionResult.FAIL else ActionResult.PASS
        }

        UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
            if (!player.world.isClient) return@register ActionResult.PASS
            val event = CancellableEvent()

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.UseEntity(hand),
                Entity.fromMC(entity),
                event,
            )

            if (event.isCancelled()) ActionResult.FAIL else ActionResult.PASS
        }

        UseItemCallback.EVENT.register { player, _, hand ->
            if (!player.world.isClient) return@register TypedActionResult.pass(null)
            val event = CancellableEvent()

            val stack = player.getStackInHand(hand)

            TriggerType.PLAYER_INTERACT.triggerAll(
                PlayerInteraction.UseItem(hand),
                Item.fromMC(stack),
                event,
            )

            if (event.isCancelled()) TypedActionResult.fail(null) else TypedActionResult.pass(null)
        }
    }

    fun addTask(delay: Int, callback: () -> Unit) {
        synchronized(tasks) {
            tasks.add(Task(delay, callback))
        }
    }

    private fun handleChatMessage(message: Text, actionBar: Boolean): Boolean {
        val textComponent = TextComponent(message)
        val event = ChatTrigger.Event(textComponent)

        return if (actionBar) {
            actionBarHistory += textComponent
            if (actionBarHistory.size > 1000)
                actionBarHistory.removeAt(0)

            TriggerType.ACTION_BAR.triggerAll(event)
            !event.isCancelled()
        } else {
            chatHistory += textComponent
            if (chatHistory.size > 1000)
                chatHistory.removeAt(0)

            TriggerType.CHAT.triggerAll(event)

            !event.isCancelled()
        }
    }

    internal fun renderTrigger(stack: MatrixStack, partialTicks: Float, block: () -> Unit) {
        Renderer.partialTicks = partialTicks
        Renderer.matrixPushCounter = 0
        Renderer.pushMatrix(UMatrixStack(stack))
        block()
        Renderer.popMatrix()

        if (Renderer.matrixPushCounter > 0) {
            "Warning: Render trigger missing a call to Renderer.popMatrix()".printToConsole(LogType.WARN)
        } else if (Renderer.matrixPushCounter < 0) {
            "Warning: Render trigger has too many calls to Renderer.popMatrix()".printToConsole(LogType.WARN)
        }
    }

    sealed class PlayerInteraction(val name: String, val mainHand: Boolean) {
        object AttackBlock : PlayerInteraction("AttackBlock", true)
        object AttackEntity : PlayerInteraction("AttackEntity", true)
        object BreakBlock : PlayerInteraction("BreakBlock", true)
        class UseBlock(hand: Hand) : PlayerInteraction("UseBlock", hand == Hand.MAIN_HAND)
        class UseEntity(hand: Hand) : PlayerInteraction("UseEntity", hand == Hand.MAIN_HAND)
        class UseItem(hand: Hand) : PlayerInteraction("UseItem", hand == Hand.MAIN_HAND)

        override fun toString(): String = name
    }
}
