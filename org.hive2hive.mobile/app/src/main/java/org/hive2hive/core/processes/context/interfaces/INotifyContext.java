package org.hive2hive.core.processes.context.interfaces;

import org.hive2hive.core.processes.notify.BaseNotificationMessageFactory;

import java.util.Set;

public interface INotifyContext {

	BaseNotificationMessageFactory consumeMessageFactory();

	Set<String> consumeUsersToNotify();
}
