package com.cretas.aims.security;

import java.lang.annotation.*;

/** Marks an entry point whose record queries and writes enforce BusinessRowScope. */
@Target({ElementType.TYPE,ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RowScopeSupported {}
