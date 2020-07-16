// Part of Hookless: https://hookless.machinezoo.com
/*
 * Diagnostic functions supported by all reactive objects:
 * - Null check is performed on method parameters where appropriate.
 * - Exceptions from application-defined methods (especially equals()) are tolerated and always handled in some way.
 * - If the reactive object has no way to propagate exceptions, it will log them. There's no other logging by default.
 * - Metrics are exposed only by reactive objects that generate events, i.e. the ones that sit at the bottom of the stack.
 * - Opentracing spans are created only where absolutely necessary, i.e. in ReactiveVariable and ReactiveTrigger.
 * - Object's OwnerTrace has at least an alias. Identifying parameters of the object are added as tags.
 * - Child reactive objects have their OwnerTrace parent set.
 * - Method toString() is defined. It uses OwnerTrace.toString().
 * - Method toString() may create reactive dependencies, which may result in harmless phantom dependencies during debugging.
 */
/**
 * Reactive primitives and core reactive classes.
 * 
 * @see <a href="https://hookless.machinezoo.com/">Hookless website</a>
 */
package com.machinezoo.hookless;
