package org.hive2hive.core.events.framework.interfaces.file;

import org.hive2hive.core.events.framework.IEvent;

import java.io.File;

public interface IFileEvent extends IEvent {
	File getFile();

	boolean isFile();

	boolean isFolder();
}
