package example.nativefixture;

import java.util.AbstractList;
import net.minecraft.util.Identifier;

final class RegistryView extends AbstractList<Identifier> {
    private final Identifier[] candidates;

    RegistryView(Identifier[] candidates) {
        this.candidates = candidates;
    }

    @Override public Identifier get(int index) { return candidates[index]; }

    @Override public int size() { return candidates.length; }

    int rankPresent(Identifier identifier, int salt) {
        if (!super.contains(identifier)) return salt;
        return Kernel.rankRegistryCandidates(candidates, salt);
    }
}
