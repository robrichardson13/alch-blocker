package io.robrichardson.alchblocker.config;

public enum UnlistedItemPolicy {
	/** Was ListType.BLACKLIST: only the blacklist blocks; everything else is alchable. */
	ALLOW,
	/** Was ListType.WHITELIST: only the whitelist can alch; everything else is blocked. */
	BLOCK
}
