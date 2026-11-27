package fixture.kotlin

import java.lang.reflect.Modifier
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.primaryConstructor

data class KotlinFixture(val count: Int) {
    fun calcPrimitive(left: Int, right: Int): Int =
        ((left + right) * 13) xor (left ushr right)

    fun calcLambda(value: Int): Int {
        val operation: (Int) -> Int = { argument -> calcPrimitive(argument, value) }
        return operation(3)
    }

    fun calcCallableReference(value: Int): Int {
        val property = this::count
        return property.get() + value
    }
}

fun calcStatic(left: Long, right: Long, shift: Int): Long = (left xor right) shl shift

fun main(args: Array<String>) {
    val protectedJar = args.single() == "protected"
    val instance = 12.let { KotlinFixture(it) }
    val clazz = KotlinFixture::class
    val primitive = clazz.java.getDeclaredMethod("calcPrimitive", Int::class.java, Int::class.java)
    val lambda = clazz.java.getDeclaredMethod("calcLambda", Int::class.java)
    val reference = clazz.java.getDeclaredMethod("calcCallableReference", Int::class.java)
    val static = Class.forName("fixture.kotlin.KotlinFixtureKt")
        .getDeclaredMethod("calcStatic", Long::class.java, Long::class.java, Int::class.java)
    check(Modifier.isNative(primitive.modifiers) == protectedJar)
    check(Modifier.isNative(static.modifiers) == protectedJar)
    check(Modifier.isNative(lambda.modifiers) == protectedJar)
    check(Modifier.isNative(reference.modifiers) == protectedJar)
    check(clazz.isData && clazz.primaryConstructor?.parameters?.single()?.name == "count")
    check(clazz.primaryConstructor?.call(12) == instance)
    val reflected = clazz.declaredMemberFunctions.single { it.name == "calcPrimitive" }
    check(reflected.call(instance, 3, 4) == 91)
    check(instance.calcPrimitive(3, 4) == 91)
    check(instance.calcLambda(4) == 91)
    check(instance.calcLambda(7) == 130)
    check(instance.calcCallableReference(7) == 19)
    check(calcStatic(12L, 5L, 3) == 72L)
    check(calcStatic(12L, 5L, 65) == 18L)
    println("primitive=91, lambda=91, reference=19, static=72/18, metadata=count")
}
