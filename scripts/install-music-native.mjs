import {cp, copyFile, mkdir, readFile, writeFile} from 'node:fs/promises';

const KOTLIN = '2.4.20';          // sama dengan Metrolist (gradle/libs.versions.toml)
const KTOR = '3.6.0';
const MARK = '// == ruang-music (install-music-native.mjs) ==';

// 1) Salin source native (Java + Kotlin + aset PoToken) ke project Android hasil `cap add android`.
const javaDir = 'android/app/src/main/java/com/ruanghari/app';
await mkdir(javaDir, {recursive: true});
await cp('native/android/com/ruanghari/app', javaDir, {recursive: true});
await mkdir('android/app/src/main/assets', {recursive: true});
await copyFile('native/android/assets/po_token.html', 'android/app/src/main/assets/po_token.html');
await writeFile(javaDir + '/MainActivity.java', `package com.ruanghari.app;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
public class MainActivity extends BridgeActivity {
  @Override public void onCreate(Bundle savedInstanceState) {
    registerPlugin(MetrolistMusicPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
`);
console.log('Plugin Metrolist (Java + Kotlin + po_token.html) terpasang.');

// 2) Gradle root: plugin Kotlin + repo JitPack (tempat InnerTubeX).
const rootPath = 'android/build.gradle';
let root = await readFile(rootPath, 'utf8');
if (!root.includes(MARK)) {
  const classpath = /(classpath\s+['"]com\.android\.tools\.build:gradle:[^'"]+['"])/;
  if (!classpath.test(root)) throw new Error('android/build.gradle: baris classpath AGP tidak ditemukan, template Capacitor berubah.');
  root = root.replace(classpath, `$1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:${KOTLIN}'`);
  root += `\n${MARK}\nallprojects {\n    repositories {\n        maven { url 'https://jitpack.io' }\n    }\n}\n`;
  await writeFile(rootPath, root);
}

// 3) Gradle app: Kotlin, desugaring, dependency.
const appPath = 'android/app/build.gradle';
let app = await readFile(appPath, 'utf8');
if (!app.includes(MARK)) {
  const plugin = /apply plugin: ['"]com\.android\.application['"]/;
  if (!plugin.test(app)) throw new Error('android/app/build.gradle: apply plugin application tidak ditemukan.');
  app = app.replace(plugin, m => `${m}\napply plugin: 'org.jetbrains.kotlin.android'`);
  app += `
${MARK}
android {
    compileOptions { coreLibraryDesugaringEnabled true }
    packaging {
        resources {
            excludes += ['META-INF/{AL2.0,LGPL2.1}', 'META-INF/INDEX.LIST', 'META-INF/DEPENDENCIES', 'META-INF/io.netty.versions.properties']
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
dependencies {
    implementation 'com.github.MetrolistGroup.innertubex:innertubex:v0.11.2'
    implementation 'io.ktor:ktor-client-core:${KTOR}'
    implementation 'io.ktor:ktor-client-okhttp:${KTOR}'
    implementation 'io.ktor:ktor-client-content-negotiation:${KTOR}'
    implementation 'io.ktor:ktor-serialization-kotlinx-json:${KTOR}'
    implementation 'io.ktor:ktor-client-encoding:${KTOR}'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0'
    implementation 'com.jakewharton.timber:timber:5.0.1'
    implementation 'androidx.media3:media3-exoplayer:1.5.1'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs_nio:2.1.5'
}
`;
  await writeFile(appPath, app);
}

// 4) minSdk 26 (syarat Metrolist / InnerTubeX); template Capacitor 7 memakai 23.
const varsPath = 'android/variables.gradle';
let vars = await readFile(varsPath, 'utf8');
if (/minSdkVersion\s*=\s*\d+/.test(vars)) {
  vars = vars.replace(/minSdkVersion\s*=\s*\d+/, 'minSdkVersion = 26');
  await writeFile(varsPath, vars);
} else throw new Error('android/variables.gradle: minSdkVersion tidak ditemukan.');

// 5) Izin tambahan di manifest.
const manifestPath = 'android/app/src/main/AndroidManifest.xml';
let manifest = await readFile(manifestPath, 'utf8');
for (const permission of ['android.permission.ACCESS_NETWORK_STATE', 'android.permission.WAKE_LOCK']) {
  if (!manifest.includes(permission)) manifest = manifest.replace('<application', `<uses-permission android:name="${permission}"/>\n    <application`);
}
await writeFile(manifestPath, manifest);

console.log('Gradle (Kotlin, JitPack, InnerTubeX, Ktor, Media3), minSdk 26, dan izin manifest siap.');

const fs = await import('node:fs/promises');

const variables = 'android/variables.gradle';
let text = await fs.readFile(variables, 'utf8');

text = text.replace(
  /compileSdkVersion\s*=\s*\d+/,
  'compileSdkVersion = 37'
);

await fs.writeFile(variables, text);

const build = 'android/build.gradle';
let gradle = await fs.readFile(build, 'utf8');

gradle = gradle.replace(
  /com\.android\.tools\.build:gradle:[\d.]+/,
  'com.android.tools.build:gradle:8.13.2'
);

await fs.writeFile(build, gradle);

const wrapper =
  'android/gradle/wrapper/gradle-wrapper.properties';

let properties = await fs.readFile(wrapper, 'utf8');

properties = properties.replace(
  /gradle-[\d.]+-(all|bin)\.zip/,
  'gradle-8.13-bin.zip'
);

await fs.writeFile(wrapper, properties);
