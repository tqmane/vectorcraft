package ai.craft.android;

public final class PalmRejectionTest {
    public static void main(String[] args) {
        PalmRejection state = new PalmRejection();
        assert !state.finger(0, 0) : "Finger-only navigation must work";
        state.pen(9, 10);
        assert state.finger(0, 20) : "Reject palms during pen hover";
        state.pen(0, 30);
        assert state.finger(2, 10000) : "Stationary pen contact must still reject palms";
        state.pen(1, 10001);
        state.pen(10, 10002);
        assert state.finger(2, 11000) : "Do not promote an existing palm into a new stroke";
        assert state.finger(1, 11001);
        assert !state.finger(0, 11002) : "Fresh touch must recover after pen leaves";
        state.pen(0, 12000);
        state.cancelFingerSequence();
        state.pen(3, 12001);
        state.reset();
        assert !state.finger(0, 12002) : "Lifecycle cancellation must clear contact/proximity";
        System.out.println("Palm rejection: passed");
    }
}
