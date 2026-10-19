package mage.abilities.effects.common.continuous;

import mage.abilities.ActivatedAbility;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * Serializable replacement for {@code Consumer<ActivatedAbility>}.
 * <p>
 * Java lambdas (e.g. {@code activatedAbility -> { ... }}) compile to non-serializable
 * lambda objects. When stored in an effect that gets serialized for game replays,
 * this causes {@link java.io.NotSerializableException}. This interface combines
 * {@link Consumer} with {@link Serializable} so that the JVM serializes the implementing
 * lambda correctly.
 *
 * @see GainAbilityWithAttachmentEffect
 */
@FunctionalInterface
public interface SerializableAbilityConsumer extends Consumer<ActivatedAbility>, Serializable {
}