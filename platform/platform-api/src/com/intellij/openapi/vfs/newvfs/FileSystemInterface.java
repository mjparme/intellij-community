/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * @author max
 */
package com.intellij.openapi.vfs.newvfs;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface FileSystemInterface {
  boolean exists(@NotNull VirtualFile fileOrDirectory);

  @NotNull
  String[] list(@NotNull VirtualFile file);

  boolean isDirectory(@NotNull VirtualFile file);

  long getTimeStamp(@NotNull VirtualFile file);
  void setTimeStamp(@NotNull VirtualFile file, long timeStamp) throws IOException;

  boolean isWritable(@NotNull VirtualFile file);
  void setWritable(@NotNull VirtualFile file, boolean writableFlag) throws IOException;

  boolean isSymLink(@NotNull VirtualFile file);

  boolean isSpecialFile(@NotNull VirtualFile file);

  VirtualFile createChildDirectory(@Nullable Object requestor, @NotNull VirtualFile parent, @NotNull String dir) throws IOException;
  VirtualFile createChildFile(@Nullable Object requestor, @NotNull VirtualFile parent, @NotNull String file) throws IOException;

  void deleteFile(final Object requestor, @NotNull VirtualFile file) throws IOException;
  void moveFile(final Object requestor, @NotNull VirtualFile file, @NotNull VirtualFile newParent) throws IOException;
  void renameFile(final Object requestor, @NotNull VirtualFile file, @NotNull String newName) throws IOException;
  VirtualFile copyFile(final Object requestor, @NotNull VirtualFile file, @NotNull VirtualFile newParent, @NotNull String copyName) throws IOException;

  @NotNull
  byte[] contentsToByteArray(@NotNull VirtualFile file) throws IOException;

  @NotNull
  InputStream getInputStream(@NotNull VirtualFile file) throws IOException;
  @NotNull 
  OutputStream getOutputStream(@NotNull VirtualFile file, final Object requestor, final long modStamp, final long timeStamp) throws IOException;

  long getLength(@NotNull VirtualFile file);
}