package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import java.lang.reflect.InvocationTargetException

/** Handle whose signature remains safe on Feature API 2.0-2.5 runtimes. */
internal interface AllianceCapitalProviderRegistration : AutoCloseable {
    val active: Boolean

    fun requestRefresh()
}

internal object NoAllianceCapitalProviderRegistration : AllianceCapitalProviderRegistration {
    override val active: Boolean = false
    override fun requestRefresh() = Unit
    override fun close() = Unit
}

/** Defers every 2.6 type link until the Host proves the complete Alliance Capital API is present. */
internal object AllianceCapitalProviderBridge {
    private const val ADAPTER_CLASS = "dev.evestaticmapplanner.sovereignty.AllianceCapitalProviderAdapter"
    private val requiredApiClasses = listOf(
        "dev.evestaticmapplanner.feature.api.AllianceCapitalProvider",
        "dev.evestaticmapplanner.feature.api.AllianceCapitalRecordDto",
        "dev.evestaticmapplanner.feature.api.AllianceCapitalSnapshotDto",
        "dev.evestaticmapplanner.feature.api.AllianceCapitalCapability",
        "dev.evestaticmapplanner.feature.api.AllianceCapitalRegistration",
    )

    fun register(
        context: FeaturePackContext,
        publicationState: SovereigntyPublicationState,
        refreshRequest: SovereigntyRefreshRequest,
    ): AllianceCapitalProviderRegistration {
        val classLoader = AllianceCapitalProviderBridge::class.java.classLoader
        if (!requiredApiClasses.all { isPresent(it, classLoader) }) {
            context.logger().log(
                FeaturePackLogLevel.INFO,
                "Host Feature API predates Alliance Capital; continuing without capital publication",
                null,
            )
            return NoAllianceCapitalProviderRegistration
        }
        return try {
            val adapter = Class.forName(ADAPTER_CLASS, true, classLoader)
            val method = adapter.getDeclaredMethod(
                "register",
                FeaturePackContext::class.java,
                SovereigntyPublicationState::class.java,
                SovereigntyRefreshRequest::class.java,
            )
            method.invoke(null, context, publicationState, refreshRequest) as AllianceCapitalProviderRegistration
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            rethrowAllianceCapitalBridgeFatal(cause)
            context.logger().log(
                FeaturePackLogLevel.WARN,
                "Alliance Capital registration is unavailable; typed Sovereignty remains active",
                cause,
            )
            NoAllianceCapitalProviderRegistration
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
private fun rethrowAllianceCapitalBridgeFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}
