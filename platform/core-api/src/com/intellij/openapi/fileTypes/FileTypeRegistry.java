// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.fileTypes;

import com.intellij.lang.Language;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.util.io.ByteSequence;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * @author yole
 */
public abstract class FileTypeRegistry {
  public static Getter<FileTypeRegistry> ourInstanceGetter;

  public abstract boolean isFileIgnored(@NotNull VirtualFile file);

  /**
   * Checks if the given file has the given file type. This is faster than getting the file type
   * and comparing it, because for file types that are identified by virtual file, it will only
   * check if the given file type matches, and will not run other detectors. However, this can
   * lead to inconsistent results if two file types report the same file as matching (which should
   * generally be avoided).
   */
  public abstract boolean isFileOfType(@NotNull VirtualFile file, @NotNull FileType type);

  @Nullable
  public LanguageFileType findFileTypeByLanguage(@NotNull Language language) {
    return language.findMyFileType(getRegisteredFileTypes());
  }

  public static FileTypeRegistry getInstance() {
    return ourInstanceGetter.get();
  }

  /**
   * Returns the list of all registered file types.
   *
   * @return The list of file types.
   */
  @NotNull
  public abstract FileType[] getRegisteredFileTypes();

  /**
   * Returns the file type for the specified file.
   *
   * @param file The file for which the type is requested.
   * @return The file type instance.
   */
  @NotNull
  public abstract FileType getFileTypeByFile(@NotNull VirtualFile file);

  /**
   * Returns the file type for the specified file.
   *
   * @param file The file for which the type is requested.
   * @param content Content of the file (if already available, to avoid reading from disk again)
   * @return The file type instance.
   */
  @NotNull
  public FileType getFileTypeByFile(@NotNull VirtualFile file, @Nullable byte[] content) {
    return getFileTypeByFile(file);
  }

  /**
   * Returns the file type for the specified file name.
   *
   * @param fileNameSeq The file name for which the type is requested.
   * @return The file type instance, or {@link FileTypes#UNKNOWN} if not found.
   */
  @NotNull
  public FileType getFileTypeByFileName(@NotNull @NonNls CharSequence fileNameSeq) {
    return getFileTypeByFileName(fileNameSeq.toString());
  }

  /**
   * Same as {@linkplain FileTypeRegistry#getFileTypeByFileName(CharSequence)} but receives String parameter.
   *
   * Consider to use the method above in case when you want to get VirtualFile's file type by file name.
   */
  @NotNull
  public abstract FileType getFileTypeByFileName(@NotNull @NonNls String fileName);

  /**
   * Returns the file type for the specified extension.
   * Note that a more general way of obtaining file type is with {@link #getFileTypeByFile(VirtualFile)}
   *
   * @param extension The extension for which the file type is requested, not including the leading '.'.
   * @return The file type instance, or {@link UnknownFileType#INSTANCE} if corresponding file type not found
   */
  @NotNull
  public abstract FileType getFileTypeByExtension(@NonNls @NotNull String extension);

  /**
   * Finds a file type with the specified name.
   */
  @Nullable
  public abstract FileType findFileTypeByName(@NotNull String fileTypeName);

  /**
   * Pluggable file type detector by content
   */
  public interface FileTypeDetector {
    ExtensionPointName<FileTypeDetector> EP_NAME = ExtensionPointName.create("com.intellij.fileTypeDetector");
    /**
     * Detects file type by its content
     * @param file to analyze
     * @param firstBytes of the file for identifying its file type
     * @param firstCharsIfText - characters, converted from first bytes parameter if the file content was determined to be text, or null otherwise
     * @return detected file type, or null if was unable to detect
     */
    @Nullable
    FileType detect(@NotNull VirtualFile file, @NotNull ByteSequence firstBytes, @Nullable CharSequence firstCharsIfText);

    /**
     * Returns the file type that this detector is capable of detecting, or null if it can detect
     * multiple file types.
     */
    @Nullable
    default Collection<? extends FileType> getDetectedFileTypes() {
      return null;
    }

    /**
     * Defines how much content is required for this detector to detect file type reliably. At least such amount of bytes
     * will be passed to {@link #detect(VirtualFile, ByteSequence, CharSequence)} if present.
     *
     * @return number of first bytes to be given
     */
    default int getDesiredContentPrefixLength() {
      return 1024;
    }

    int getVersion();
  }
}
