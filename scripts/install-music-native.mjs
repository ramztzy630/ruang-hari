import {copyFile, mkdir, readFile, writeFile} from 'node:fs/promises';
const dir='android/app/src/main/java/com/ruanghari/app';
await mkdir(dir,{recursive:true});
await copyFile('native/android/com/ruanghari/app/MetrolistMusicPlugin.java',dir+'/MetrolistMusicPlugin.java');
await writeFile(dir+'/MainActivity.java',`package com.ruanghari.app;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
public class MainActivity extends BridgeActivity {
  @Override public void onCreate(Bundle savedInstanceState) {
    registerPlugin(MetrolistMusicPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
`);
console.log('Metrolist search bridge installed.');
