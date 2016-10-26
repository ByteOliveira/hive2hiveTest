package org.hive2hive.core.processes.context.interfaces;

import org.hive2hive.core.model.Index;

import java.security.KeyPair;

public interface IInitializeMetaUpdateContext {

	Index consumeIndex();

	KeyPair consumeOldProtectionKeys();

	KeyPair consumeNewProtectionKeys();

	boolean isSharedBefore();

}
