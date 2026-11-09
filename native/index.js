import { LocalNotifications } from '@capacitor/local-notifications';
export const notifications = LocalNotifications;
import { registerPlugin } from '@capacitor/core';
export const music = registerPlugin('MetrolistMusic');
