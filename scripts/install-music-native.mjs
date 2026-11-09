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

const gradle='android/app/build.gradle';
let config=await readFile(gradle,'utf8');
if(!config.includes('androidx.media3:media3-exoplayer:')) {
  config += `
// Native Media3 audio engine
dependencies {
    implementation 'androidx.media3:media3-exoplayer:1.5.1'
}
`;
  await writeFile(gradle,config);
}
console.log('Media3 player dependency installed.');
