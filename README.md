# Universal Storage Java API
## Dropbox provider

[![Build Status](https://travis-ci.org/dynamicloud/universal_storage_java_dropbox_api.svg?branch=master)](https://travis-ci.org/dynamicloud/universal_storage_java_dropbox_api)
![Version](https://img.shields.io/badge/api-v1.0.0-brightgreen.svg)

Universal storage provides you an interface for storing files according to your needs. With this Universal Storage Java API, you will be able to develop programs in Java and use an interface for storing your files within a app folder as storage.

<hr>

**This documentation has the following content:**

1. [Maven project](maven-project)
2. [Test API](#test-api)
3. [Settings](#settings)
4. [Dropbox App](#dropbox-app)
5. [How to use](#how-to-use)

# Maven project
This API follows the Maven structure to ease its installation within your project.

# Test API
If you want to test the API, follow these steps:

1. Create a folder and copy its the absolute path.  This folder will be your storage root target.
2. Create a folder and copy its the absolute path.  This folder will be your tmp folder.
3. Open with a text editor the settings.json located on test/resources/settings.json
```json
{
	"provider": "dropbox",
	"root": "storage",
	"tmp": "src/test/resources/tmp",
	"dropbox": {
		"access_token": ""
	}
}
```
4. Paste the absolute paths, the root's path and the tmp's path.
5. Save the settings.json file.

**Now execute the following command:**

`mvn clean test` 

# Settings
**These are the steps for setting up Universal Storage in your project:**
1. You must create a file called settings.json (can be any name) and paste the following. 
```json
{
	"provider": "dropbox",
	"root": "storage",
	"tmp": "src/test/resources/tmp",
	"dropbox": {
		"access_token": ""
	}
}
```
2. The root and tmp keys are the main data to be filled, create two folders representing each one root and tmp.
3. Save the file settings.json
4. Add the maven dependency in your pom.xml file.

```xml
<dependency>
   <groupId>org.dynamicloud.api</groupId>
   <artifactId>universalstorage.dropbox</artifactId>
   <version>1.0.0</version>
</dependency>
```
5. Create the tmp folder according to the entered path into the settings.json file.

The root folder is the storage where the files will be stored.
The tmp folder is where temporary files will be stored.

# Dropbox App
In order to use Dropbox as a storage, you need to create an App and generate an access token.

1. [Create app](https://www.dropbox.com/developers/apps) on Dropbox site.
2. After app creation, generate an access token.
<img src="https://s3.amazonaws.com/shared-files-2017/generate_at__dropbox.png"/>
3. Copy the generated access token and paste it into the settings.json file.

```json
{
	"provider": "dropbox",
	"root": "storage",
	"tmp": "src/test/resources/tmp",
	"dropbox": {
		"access_token": "HERE"
	}
}
```

### This api will get the access token through either this file or using the environment variable `access_token`

4. Now you need to go to the application folder (The name of this folder is the app's name) and create your root folder inside of it, for example:

<img src="https://s3.amazonaws.com/shared-files-2017/root_folder_at__dropbox.png"/>

# How to use
**Examples for Storing files:**

1. Passing the settings programmatically
```java
try {
      UniversalStorage us = UniversalStorage.Impl.
          getInstance(new UniversalSettings(new File("/home/test/resources/settings.json")));
      us.storeFile(new File("/home/test/resources/settings.json"), "myfolder/innerfolder");
      us.storeFile(new File("/home/test/resources/settings.json"));
      us.storeFile(new File("/home/test/resources/settings.json").getAbsolutePath(), "myfolder/innerfolder");
      us.storeFile(new File("/home/test/resources/settings.json").getAbsolutePath());
} catch (UniversalStorageException e) {
    fail(e.getMessage());
}
```
2. The settings could be passed through either jvm parameter or environment variable.
3. If you want to pass the settings.json path through jvm parameter, in your java command add the following parameter:
     `-Duniversal.storage.settings=/home/test/resources/settings.json`
4. If your want to pass the settings.json path through environment variable, add the following variable:
     `universal_storage_settings=/home/test/resources/settings.json`

```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      us.storeFile(new File("/home/test/resources/settings.json"), "myfolder/innerfolder");
      us.storeFile(new File("/home/test/resources/settings.json"));
      us.storeFile(new File("/home/test/resources/settings.json").getAbsolutePath(), "myfolder/innerfolder");
      us.storeFile(new File("/home/test/resources/settings.json").getAbsolutePath());
} catch (UniversalStorageException e) {
    fail(e.getMessage());
}
```

**Remove file:**
```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      us.removeFile("/home/test/resources/settings.json");
} catch (UniversalStorageException e) {
    e.printStackTrace();
}

```

**Create folder:**

```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      us.createFolder("/myNewFolder");
} catch (UniversalStorageException e) {
    e.printStackTrace();
}

```

**Remove folder:**
```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      us.removeFolder("/myNewFolder");
} catch (UniversalStorageException e) {
    e.printStackTrace();
}
```

**Retrieve file:**

This file will be stored into the tmp folder.
```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      File file = us.retrieveFile("myFolder/file.txt");
} catch (UniversalStorageException e) {
    e.printStackTrace();
}
```

**Retrieve file as InputStream:**

This inputstream will use a file that was stored into the tmp folder.
```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      InputSstream stream = us.retrieveFileAsStream("myFolder/file.txt");
} catch (UniversalStorageException e) {
    e.printStackTrace();
}
```

**Clean up tmp folder:**
```java
try {
      UniversalStorage us = UniversalStorage.Impl.getInstance();
      us.clean();
} catch (UniversalStorageException e) {
    e.printStackTrace();
}
```
