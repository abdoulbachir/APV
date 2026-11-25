package org.apvgakasinay.fixtures;

/** A fixture file that cannot be loaded. */
public class FixtureException extends RuntimeException {

    public FixtureException(String message) {
        super(message);
    }
}
