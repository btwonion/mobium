package dev.nyon.mobium.debug

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.mixins.FrontendGpuDeviceAccessor
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTDebugUtils
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkLayerProperties

/**
 * Dev-only check that Mobium raises no Vulkan validation messages (`PLAN.md` M7, Gate D). When a dev client runs on
 * Vulkan with Khronos' validation layer (`-Pmobium.vulkanValidation`, which passes `--vulkanValidation` and the system
 * property `mobium.vulkanValidation`), [installIfRequested] adds a debug messenger of Mobium's own next to
 * Renderpearl's, which logs only what `glDebugVerbosity` asks for (`docs/vanilla-notes.md` §21). Every warning and
 * error is sorted: Mobium's when an object it names or its text mentions Mobium (Mobium labels its objects "Mobium …",
 * and the layer passes labels on as object names), or when the call that raised it came from Mobium's code, i.e. a
 * frame of `dev.nyon.mobium` or a mixin handler `mobium$…` among the first [CALLER_FRAMES] frames outside LWJGL and
 * Renderpearl, up to the self-check or gametest driving the game. The self-check fails on Mobium's messages and notes
 * the others. Render thread for install and uninstall; the layer calls back on the thread of the failing call.
 */
object VulkanValidation {
    const val LAYER = "VK_LAYER_KHRONOS_validation"
    private const val CALLER_FRAMES = 8
    private const val KEPT = 50
    private const val SEVERITIES =
        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
    private const val TYPES = EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
    private val OWN_CLASS = VulkanValidation::class.java.name
    private val DRIVERS = arrayOf("dev.nyon.mobium.debug.selfcheck.", "dev.nyon.mobium.test.")
    private val walker = StackWalker.getInstance()

    /** Whether the launch asked for the validation layer. */
    val requested: Boolean
        get() = System.getProperty("mobium.vulkanValidation").toBoolean()

    /** Whether the layer is installed; Minecraft enables it when [requested] (`VulkanInstance`). */
    var layerPresent = false
        private set

    /** Whether Mobium's messenger is listening. */
    var installed = false
        private set

    var mobiumMessages = 0
        private set
    var otherMessages = 0
        private set

    /** The first [KEPT] messages of each kind, with the Mobium frame that made a message Mobium's. */
    val keptMobium = ArrayList<String>()
    val keptOther = ArrayList<String>()

    private var instance: VkInstance? = null
    private var messenger = 0L
    private var callback: VkDebugUtilsMessengerCallbackEXT? = null

    /** Called once after the GPU device exists; does nothing outside dev environments, on OpenGL or unasked. */
    fun installIfRequested() {
        if (!Compat.isDevelopment || !requested || installed) return
        val device = (RenderSystem.getDevice() as? FrontendGpuDeviceAccessor)?.`mobium$backend`() as? VulkanDevice ?: return
        layerPresent = hasLayer()
        val vulkanInstance = device.instance()
        if (EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME !in vulkanInstance.enabledExtensions) {
            Mobium.LOGGER.warn("Vulkan validation: VK_EXT_debug_utils is not enabled, no messages can be checked")
            return
        }
        val callback = VkDebugUtilsMessengerCallbackEXT.create(::onMessage)
        MemoryStack.stackPush().use { stack ->
            val info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).`sType$Default`()
                .messageSeverity(SEVERITIES)
                .messageType(TYPES)
                .pfnUserCallback(callback)
            val handle = stack.callocLong(1)
            val result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.vkInstance(), info, null, handle)
            if (result != VK10.VK_SUCCESS) {
                callback.free()
                Mobium.LOGGER.warn("Vulkan validation: vkCreateDebugUtilsMessengerEXT failed ({})", result)
                return
            }
            messenger = handle.get(0)
        }
        this.callback = callback
        instance = vulkanInstance.vkInstance()
        installed = true
        Mobium.LOGGER.info("Vulkan validation: listening (layer {})", if (layerPresent) "present" else "missing")
    }

    /** Removes the messenger before the Vulkan instance goes away (`Minecraft.close`, the self-check's end). */
    @JvmStatic
    fun uninstall() {
        val instance = instance ?: return
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null)
        callback?.free()
        callback = null
        this.instance = null
        messenger = 0L
        installed = false
    }

    private fun hasLayer(): Boolean = MemoryStack.stackPush().use { stack ->
        val count = stack.callocInt(1)
        if (VK10.vkEnumerateInstanceLayerProperties(count, null) != VK10.VK_SUCCESS) return false
        val layers = VkLayerProperties.malloc(count.get(0), stack)
        if (VK10.vkEnumerateInstanceLayerProperties(count, layers) != VK10.VK_SUCCESS) return false
        (0 until layers.capacity()).any { layers.get(it).layerNameString() == LAYER }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onMessage(severity: Int, types: Int, callbackData: Long, userData: Long): Int {
        val data = VkDebugUtilsMessengerCallbackDataEXT.create(callbackData)
        val text = data.pMessageString()
        val names = data.pObjects()?.let { objects -> (0 until data.objectCount()).mapNotNull { objects.get(it).pObjectNameString() } }.orEmpty()
        val named = mentionsMobium(text) || names.any(::mentionsMobium)
        val caller = if (named) null else mobiumCaller()
        val kind = if (severity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT != 0) "error" else "warning"
        val line = "$kind ${data.pMessageIdNameString() ?: "-"}: $text"
        if (named || caller != null) {
            mobiumMessages++
            val entry = if (caller != null) "$line (called from $caller)" else line
            if (keptMobium.size < KEPT) keptMobium += entry
            Mobium.LOGGER.error("Vulkan validation, Mobium's: {}", entry)
        } else {
            otherMessages++
            if (keptOther.size < KEPT) keptOther += line
        }
        return VK10.VK_FALSE
    }

    private fun mentionsMobium(text: String?) = text != null && text.contains("mobium", ignoreCase = true)

    /** The first Mobium frame among the callers of the Vulkan call, or null. */
    private fun mobiumCaller(): String? = walker.walk { frames ->
        frames.filter { frame ->
            val name = frame.className
            !name.startsWith(OWN_CLASS) && !name.startsWith("org.lwjgl.") && !name.startsWith("com.mojang.renderpearl.")
        }
            .takeWhile { frame -> DRIVERS.none { frame.className.startsWith(it) } }
            .limit(CALLER_FRAMES.toLong())
            .filter { frame -> frame.className.startsWith("dev.nyon.mobium.") || frame.methodName.startsWith("mobium$") }
            .findFirst()
            .map { it.toString() }
            .orElse(null)
    }
}
