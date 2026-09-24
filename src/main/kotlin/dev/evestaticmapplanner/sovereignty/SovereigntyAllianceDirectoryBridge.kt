package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import java.lang.reflect.InvocationTargetException

/** Pack-owned handle whose signature is safe on Feature API 2.0-2.3 runtimes. */
internal interface SovereigntyAllianceDirectoryRegistration : AutoCloseable {
    val active: Boolean

    fun requestRefresh()
}

internal object NoSovereigntyAllianceDirectoryRegistration : SovereigntyAllianceDirectoryRegistration {
    override val active: Boolean = false

    override fun requestRefresh() = Unit

    override fun close() = Unit
}

/**
 * Avoids linking the 2.4 Alliance Directory API until the Host classpath proves that every required type exists.
 * The adapter class name is deliberately a String so an older Host can load and start the main Pack entrypoint.
 */
internal object SovereigntyAllianceDirectoryBridge {
    private const val ADAPTER_CLASS =
        "dev.evestaticmapplanner.sovereignty.SovereigntyAllianceDirectoryAdapter"
    private val requiredApiClasses = listOf(
        "dev.evestaticmapplanner.feature.api.AllianceDirectoryProvider",
        "dev.evestaticmapplanner.feature.api.AllianceDirectoryProviderSnapshot",
        "dev.evestaticmapplanner.feature.api.AllianceDirectoryCapability",
        "dev.evestaticmapplanner.feature.api.AllianceDirectoryRegistration",
        "dev.evestaticmapplanner.feature.api.AllianceReferenceSnapshot",
    )

    fun register(
        context: FeaturePackContext,
        repository: SovereigntyRepository,
        metadataState: AllianceMetadataState,
    ): SovereigntyAllianceDirectoryRegistration {
        val classLoader = SovereigntyAllianceDirectoryBridge::class.java.classLoader
        if (!requiredApiClasses.all { isPresent(it, classLoader) }) {
            context.logger().log(
                FeaturePackLogLevel.INFO,
                "Host Feature API predates Alliance Directory; continuing without directory publication",
                null,
            )
            return NoSovereigntyAllianceDirectoryRegistration
        }
        return try {
            val adapter = Class.forName(ADAPTER_CLASS, true, classLoader)
            val method = adapter.getDeclaredMethod(
                "register",
                FeaturePackContext::class.java,
                SovereigntyRepository::class.java,
                AllianceMetadataState::class.java,
            )
            method.invoke(null, context, repository, metadataState) as SovereigntyAllianceDirectoryRegistration
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            rethrowBridgeFatal(cause)
            context.logger().log(
                FeaturePackLogLevel.WARN,
                "Alliance Directory registration is unavailable; Sovereignty publication remains active",
                cause,
            )
            NoSovereigntyAllianceDirectoryRegistration
        }
    }

    private fun isPresent(className: String, classLoader: ClassLoader): Boolean = try {
        Class.forName(className, false, classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}

@Suppress("DEPRECATION")
private fun rethrowBridgeFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}
