package com.cretas.aims.security;

/**
 * Namespace for authenticated human actors.
 *
 * <p>The numeric id is not globally unique: platform administrators ({@code platform_admins})
 * and factory users ({@code users}) live in different tables with independent sequences and can
 * legitimately have the same id. Every authorization or ownership check that accepts both kinds
 * of actor must therefore branch on this type before resolving the id.
 */
public enum AuthenticatedActorType {
    FACTORY_USER,
    PLATFORM_ADMIN
}
