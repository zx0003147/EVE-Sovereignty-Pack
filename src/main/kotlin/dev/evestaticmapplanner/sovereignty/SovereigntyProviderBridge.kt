package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import java.lang.reflect.InvocationTargetException

/** Handle whose signature is safe on Feature API 2.0-2.4 runtimes. */
internal interface SovereigntyProviderRegistration : AutoCloseable {
    val active: Boolean

    fun requestRefresh()
}

internal object NoSovereigntyProviderRegistration : SovereigntyProviderRegistration {
    override val active: Boolean = false
    override fun requestRefresh() = Unit
    override fun close() = Unit
}

/** Defers every 2.5 type link until the Host proves the complete typed API is present. */
internal object SovereigntyProviderBridge {
    private const val ADAPTER_CLASS = "dev.evestaticmapplanner.sovereignty.SovereigntyProviderAdapter"
    private val requiredApiClasses = listOf(
        "dev.evestaticmapplanner.feature.api.SovereigntyProvider",
        "dev.evestaticmapplanner.feature.api.SovereigntySnapshotDto",
        "dev.evestaticmapplanner.feature.api.SovereigntyCapability",
        "dev.evestaticmapplanner.feature.api.SovereigntyRegistration",
        "dev.evestaticmapplanner.feature.api.SystemOwnershipDto",
        "dev.evestaticmapplanner.feature.api.SovereigntyOwnerKindDto",
        "dev.evestaticmapplanner.feature.api.SovereigntyStatusDto",
        "dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto",
    )

    fun register(
        context: FeaturePackContext,
        publicationState: SovereigntyPublicationState,
        refreshRequest: SovereigntyRefreshRequest,
    ): SovereigntyProviderRegistration {
        val classLoader = SovereigntyProviderBridge::class.java.classLoader
        if (!requiredApiClasses.all { isPresent(it, classLoader) }) {
            context.logger().log(
                FeaturePackLogLevel.INFO,
                "Host Feature API predates typed Sovereignty; continuing with Overlay and System Info",
                null,
            )
            return NoSovereigntyProviderRegistration
        }
        return try {
            val adapter = Class.forName(ADAPTER_CLASS, true, classLoader)
            val method = adapter.getDeclaredMethod(
                "register",
                FeaturePackContext::class.java,
                SovereigntyPublicationState::class.java,
                SovereigntyRefreshRequest::class.java,
            )
            method.invoke(null, context, publicationState, refreshRequest) as SovereigntyProviderRegistration
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            rethrowProviderBridgeFatal(cause)
            context.logger().log(
                FeaturePackLogLevel.WARN,
                "Typed Sovereignty registration is unavailable; Overlay and System Info remain active",
                cause,
            )
            NoSovereigntyProviderRegistration
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
private fun rethrowProviderBridgeFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}
