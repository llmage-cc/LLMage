package mage.abilities.effects.common;

import mage.game.permanent.token.Token;

import java.io.Serializable;
import java.util.function.Function;

/**
 * Serializable replacement for {@code Function<Integer, Token>}.
 * <p>
 * Java method references (e.g. {@code CustomIllusionToken::new}) compile to non-serializable
 * lambda objects. When stored in an {@link OneShotEffect} that gets serialized for game replays,
 * this causes {@link java.io.NotSerializableException}. This interface combines
 * {@link Function} with {@link Serializable} so that the JVM serializes the implementing
 * lambda correctly.
 *
 * @see CreateXXTokenExiledEffectManaValueEffect
 */
@FunctionalInterface
public interface SerializableTokenMaker extends Function<Integer, Token>, Serializable {
}