package org.hive2hive.core.events.implementations;

import org.hive2hive.core.events.framework.abstracts.FileEvent;
import org.hive2hive.core.events.framework.interfaces.file.IFileAddEvent;

import java.io.File;

public class FileAddEvent extends FileEvent implements IFileAddEvent {

	public FileAddEvent(File file,  boolean isFile) {
		super(file, isFile);
	}

}
