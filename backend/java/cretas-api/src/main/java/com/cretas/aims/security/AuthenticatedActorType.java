package com.cretas.aims.security;

/**
 * Namespace for authenticated human actors.
 *
 * <p>The numeric id is not globally unique: platform administrators and factory users
 * live in different tables and can legitimately have the same id. Every authorization
 * or ownership key that accepts both kinds of actor must therefore include this type.
 */
public enum AuthenticatedActorType {
    FACTORY_USER,
    PLATFORM_ADMIN
}
