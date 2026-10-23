

package mage.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

/**
 *
 * @author BetaSteward_at_googlemail.com
 */
public class CopierObjectInputStream extends ObjectInputStream {
    ClassLoader myLoader = null;

    public CopierObjectInputStream(ClassLoader newLoader, InputStream theStream) throws IOException {
        super(theStream);
        myLoader = newLoader;
    }

    @Override
    protected Class resolveClass(ObjectStreamClass osc) throws IOException, ClassNotFoundException
    {
        try {
            return Class.forName(osc.getName(), true, myLoader);
        }
        catch (ClassNotFoundException e) {
            throw new ClassNotFoundException(osc.getName(), e);
        }
    }

}
