/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.internal;

import kotlin.SinceKotlin;
import kotlin.reflect.KCallable;
import kotlin.reflect.KProperty;

@SuppressWarnings("rawtypes")
public abstract class PropertyReference extends CallableReference implements KProperty {
    private final boolean syntheticJavaProperty;

    public PropertyReference() {
        super();

        syntheticJavaProperty = false;
    }

    @SinceKotlin(version = "1.1")
    public PropertyReference(Object receiver) {
        super(receiver);

        syntheticJavaProperty = false;
    }

    @SinceKotlin(version = "1.4")
    public PropertyReference(Object receiver, Class owner, String name, String signature, int flags) {
        super(receiver, owner, name, signature, (flags & 1) == 1);

        syntheticJavaProperty = (flags & 2) == 2;
    }

    @Override
    @SinceKotlin(version = "1.1")
    protected KProperty getReflected() {
        if (syntheticJavaProperty) {
            throw new UnsupportedOperationException("Kotlin reflection is not yet supported for synthetic Java properties. " +
                                                    "Please follow/upvote https://youtrack.jetbrains.com/issue/KT-55980");
        }
        return (KProperty) super.getReflected();
    }

    @Override
    public KCallable compute() {
        return syntheticJavaProperty ? this : super.compute();
    }

    @Override
    @SinceKotlin(version = "1.1")
    public boolean isLateinit() {
        return getReflected().isLateinit();
    }

    @Override
    @SinceKotlin(version = "1.1")
    public boolean isConst() {
        return getReflected().isConst();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj instanceof PropertyReference) {
            PropertyReference other = (PropertyReference) obj;
            return getOwner().equals(other.getOwner()) &&
                   getName().equals(other.getName()) &&
                   getSignature().equals(other.getSignature()) &&
                   Intrinsics.areEqual(getBoundReceiver(), other.getBoundReceiver());
        }
        if (obj instanceof KProperty) {
            return obj.equals(compute());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (getOwner().hashCode() * 31 + getName().hashCode()) * 31 + getSignature().hashCode();
    }

    @Override
    public String toString() {
        KCallable reflected = compute();
        if (reflected != this) {
            return reflected.toString();
        }

        return "property " + getName() + Reflection.REFLECTION_NOT_AVAILABLE;
    }
}
