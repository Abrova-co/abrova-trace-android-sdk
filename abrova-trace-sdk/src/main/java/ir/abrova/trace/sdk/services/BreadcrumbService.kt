package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.models.Breadcrumb
import ir.abrova.trace.sdk.models.BreadcrumbType
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service for managing breadcrumbs (trail of events leading up to an error)
 * Thread-safe implementation using CopyOnWriteArrayList
 */
object BreadcrumbService {

    private val breadcrumbs = CopyOnWriteArrayList<Breadcrumb>()
    private var maxBreadcrumbs = 20

    /**
     * Configure the maximum number of breadcrumbs to store
     */
    fun setMaxBreadcrumbs(max: Int) {
        maxBreadcrumbs = max
        trimBreadcrumbs()
    }

    /**
     * Add a breadcrumb
     */
    fun add(breadcrumb: Breadcrumb) {
        breadcrumbs.add(breadcrumb)
        trimBreadcrumbs()
    }

    /**
     * Add a simple breadcrumb with message
     */
    fun add(message: String, type: BreadcrumbType = BreadcrumbType.INFO, data: Map<String, Any?> = emptyMap()) {
        add(Breadcrumb(message = message, type = type, data = data))
    }

    /**
     * Add a navigation breadcrumb
     */
    fun addNavigation(from: String?, to: String) {
        add(Breadcrumb.navigation(from, to))
    }

    /**
     * Add a user action breadcrumb
     */
    fun addUser(action: String, data: Map<String, Any?> = emptyMap()) {
        add(Breadcrumb.user(action, data))
    }

    /**
     * Add an HTTP request breadcrumb
     */
    fun addHttp(method: String, url: String, statusCode: Int? = null, duration: Long? = null) {
        add(Breadcrumb.http(method, url, statusCode, duration))
    }

    /**
     * Add a state change breadcrumb
     */
    fun addState(message: String, data: Map<String, Any?> = emptyMap()) {
        add(Breadcrumb.state(message, data))
    }

    /**
     * Add a system event breadcrumb
     */
    fun addSystem(message: String, data: Map<String, Any?> = emptyMap()) {
        add(Breadcrumb.system(message, data))
    }

    /**
     * Add an error breadcrumb
     */
    fun addError(message: String, data: Map<String, Any?> = emptyMap()) {
        add(Breadcrumb.error(message, data))
    }

    /**
     * Get all breadcrumbs
     */
    fun getBreadcrumbs(): List<Breadcrumb> {
        return breadcrumbs.toList()
    }

    /**
     * Get breadcrumbs as a list of maps (for JSON serialization)
     */
    fun getBreadcrumbsAsMap(): List<Map<String, Any?>> {
        return breadcrumbs.map { it.toMap() }
    }

    /**
     * Clear all breadcrumbs
     */
    fun clear() {
        breadcrumbs.clear()
    }

    /**
     * Get the number of stored breadcrumbs
     */
    fun size(): Int = breadcrumbs.size

    /**
     * Trim breadcrumbs to max size (remove oldest first)
     */
    private fun trimBreadcrumbs() {
        while (breadcrumbs.size > maxBreadcrumbs) {
            breadcrumbs.removeAt(0)
        }
    }
}
