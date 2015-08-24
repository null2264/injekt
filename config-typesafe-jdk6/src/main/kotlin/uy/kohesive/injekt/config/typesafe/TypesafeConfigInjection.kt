package uy.kohesive.injekt.config.typesafe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigResolveOptions
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektScope
import java.net.URI
import kotlin.properties.Delegates

/**
 * A class that startups up an system using Injekt + TypesafeConfig, using the default global scope, and default object binder
 */
public abstract class KonfigAndInjektMain(): KonfigAndInjektScopedMain(Injekt)

/**
 *  A startup module that registers and uses singletons/object factories from a specific scope,
 *  and an ObjectMapper to bind configuration properties into class instances.
 */
public abstract class KonfigAndInjektScopedMain(public val scope: InjektScope, public val mapper: ObjectMapper = jacksonObjectMapper()) : InjektModule, KonfigModule {
    private val ADDON_ID = "Konfigure"

    protected var resolvedConfig: Config by Delegates.notNull()

    abstract fun configFactory(): Config

    private data class KonfigureClassAtPath(val path: String, val klass: Class<*>)

    private inner class ScopedKonfigRegistrar(val path: List<String>, val scope: InjektScope, val itemsToConfigure: MutableList<KonfigureClassAtPath>): KonfigRegistrar {
        override fun importModule(atPath: String, module: KonfigModule) {
            module.registerWith(ScopedKonfigRegistrar(path + atPath.split('.'), scope, itemsToConfigure))
        }

        override fun bindClassAtConfigRoot(klass: Class<*>) {
            val fullpath = path.filter { it.isNotBlank() }.map { it.removePrefix(".").removeSuffix(".") }.joinToString(".")
            itemsToConfigure.add(KonfigureClassAtPath(fullpath, klass))
        }

        override fun bindClassAtConfigPath(configPath: String, klass: Class<*>) {
            val fullpath = (path + configPath.split('.')).filter { it.isNotBlank() }.map { it.removePrefix(".").removeSuffix(".") }.joinToString(".")
            itemsToConfigure.add(KonfigureClassAtPath(fullpath, klass))
        }

        @suppress("UNCHECKED_CAST")
        fun loadAndInject(config: Config) {
            itemsToConfigure.forEach {
                val configAtPath = config.getConfig(it.path)
                // TODO: handle a class that wants to be constructed with a configuration object instead of binding
                val asJson = configAtPath.root().render(ConfigRenderOptions.concise().setJson(true))
                val instance: Any = mapper.readValue(asJson, it.klass)!!
                scope.registrar.addSingleton(it.klass as Class<Any>, instance)
            }
        }
    }

    init {
        val itemsToConfigure: MutableList<KonfigureClassAtPath> = scope.getAddonMetadata(ADDON_ID) ?: scope.setAddonMetadata(ADDON_ID, linkedListOf<KonfigureClassAtPath>())
        val registrar = ScopedKonfigRegistrar(emptyList(), scope, itemsToConfigure)
        registrar.registerConfigurables()
        resolvedConfig = configFactory()
        registrar.loadAndInject(resolvedConfig)
        scope.registrar.registerInjectables()
    }
}

public interface KonfigRegistrar {
    fun importModule(atPath: String, module: KonfigModule)

    final inline fun <reified T> bindClassAtConfigPath(configPath: String) {
        bindClassAtConfigPath(configPath, javaClass<T>())
    }

    fun bindClassAtConfigPath(configPath: String, klass: Class<*>)

    final inline fun <reified T> bindClassAtConfigRoot() {
        bindClassAtConfigRoot(javaClass<T>())
    }

    fun bindClassAtConfigRoot(klass: Class<*>)
}

/**
 * A package of configuration bound items that can be included into a scope of someone else
 */
public interface KonfigModule {
    final internal fun registerWith(intoModule: KonfigRegistrar) {
        intoModule.registerConfigurables()
    }

    fun KonfigRegistrar.registerConfigurables()
}

