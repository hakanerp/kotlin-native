/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.backend.konan.llvm

import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.backend.konan.KonanVersion
import org.jetbrains.kotlin.ir.SourceManager.FileEntry
import org.jetbrains.kotlin.backend.konan.util.File
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.types.KotlinType


internal object DWARF {
    val DW_LANG_kotlin                 = 1 //TODO: we need own constant e.g. 0xbabe
    val producer                       = "konanc ${KonanVersion.CURRENT} / kotlin-compiler: ${KotlinVersion.CURRENT}"
    /* TODO: from LLVM sources is unclear what runtimeVersion corresponds to term in terms of dwarf specification. */
    val runtimeVersion                 = 2
    val dwarfVersionMetaDataNodeName   = "Dwarf Name".mdString()
    val dwarfDebugInfoMetaDataNodeName = "Debug Info Version".mdString()
    val dwarfVersion = 2 /* TODO: configurable? like gcc/clang -gdwarf-2 and so on. */
    val debugInfoVersion = 3 /* TODO: configurable? */
}


/**
 * File entry starts offsets from zero while dwarf number lines/column starting from 1.
 */
private fun FileEntry.location(offset:Int, offsetToNumber:(Int) -> Int):Int {
    return if (offset < 0) -1
    else offsetToNumber(offset) + 1
}

internal fun FileEntry.line(offset: Int) = location(offset, this::getLineNumber)

internal fun FileEntry.column(offset: Int) = location(offset, this::getColumnNumber)

internal data class FileAndFolder(val file:String, val folder:String) {
    companion object {
        val NOFILE =  FileAndFolder("-", "")
    }

    fun path() = if (this == NOFILE) file else "$folder/$file"
}

internal fun String?.toFileAndFolder():FileAndFolder {
    this ?: return FileAndFolder.NOFILE
    val file = File(this).absoluteFile
    return FileAndFolder(file.name, file.parent)
}

internal fun generateDebugInfoHeader(context: Context) {
    if (context.shouldContainDebugInfo()) {
        val path = context.config.configuration.get(KonanConfigKeys.BITCODE_FILE).toFileAndFolder()
        context.debugInfo.module = DICreateModule(
                builder = context.debugInfo.builder,
                scope = context.llvmModule as DIScopeOpaqueRef,
                name = path.path(),
                configurationMacro = "",
                includePath = "",
                iSysRoot = "")
        context.debugInfo.compilationModule = DICreateCompilationUnit(
                builder = context.debugInfo.builder,
                lang = DWARF.DW_LANG_kotlin,
                File = path.file,
                dir = path.folder,
                producer = DWARF.producer,
                isOptimized = 0,
                flags = "",
                rv = DWARF.runtimeVersion)
        /* TODO: figure out what here 2 means:
         *
         * 0:b-backend-dwarf:minamoto@minamoto-osx(0)# cat /dev/null | clang -xc -S -emit-llvm -g -o - -
         * ; ModuleID = '-'
         * source_filename = "-"
         * target datalayout = "e-m:o-i64:64-f80:128-n8:16:32:64-S128"
         * target triple = "x86_64-apple-macosx10.12.0"
         *
         * !llvm.dbg.cu = !{!0}
         * !llvm.module.flags = !{!3, !4, !5}
         * !llvm.ident = !{!6}
         *
         * !0 = distinct !DICompileUnit(language: DW_LANG_C99, file: !1, producer: "Apple LLVM version 8.0.0 (clang-800.0.38)", isOptimized: false, runtimeVersion: 0, emissionKind: FullDebug, enums: !2)
         * !1 = !DIFile(filename: "-", directory: "/Users/minamoto/ws/.git-trees/backend-dwarf")
         * !2 = !{}
         * !3 = !{i32 2, !"Dwarf Version", i32 2}              ; <-
         * !4 = !{i32 2, !"Debug Info Version", i32 700000003} ; <-
         * !5 = !{i32 1, !"PIC Level", i32 2}
         * !6 = !{!"Apple LLVM version 8.0.0 (clang-800.0.38)"}
         */
        val llvmTwo = Int32(2).llvm
        val dwarfVersion = node(llvmTwo, DWARF.dwarfVersionMetaDataNodeName, Int32(DWARF.dwarfVersion).llvm)
        val nodeDebugInfoVersion = node(llvmTwo, DWARF.dwarfDebugInfoMetaDataNodeName, Int32(DWARF.debugInfoVersion).llvm)
        val llvmModuleFlags = "llvm.module.flags"
        LLVMAddNamedMetadataOperand(context.llvmModule, llvmModuleFlags, dwarfVersion)
        LLVMAddNamedMetadataOperand(context.llvmModule, llvmModuleFlags, nodeDebugInfoVersion)
    }
}

internal fun KotlinType.dwarfType(context:Context, targetData:LLVMTargetDataRef): DITypeOpaqueRef {
    return when {
        KotlinBuiltIns.isInt(this)              -> debugInfoBaseType(context, targetData, "Int",     LLVMInt32Type()!!)
        KotlinBuiltIns.isBoolean(this)          -> debugInfoBaseType(context, targetData, "Boolean", LLVMInt1Type()!!)
        KotlinBuiltIns.isChar(this)             -> debugInfoBaseType(context, targetData, "Char",    LLVMInt8Type()!!)
        KotlinBuiltIns.isShort(this)            -> debugInfoBaseType(context, targetData, "Short",   LLVMInt16Type()!!)
        KotlinBuiltIns.isByte(this)             -> debugInfoBaseType(context, targetData, "Byte",    LLVMInt8Type()!!)
        KotlinBuiltIns.isLong(this)             -> debugInfoBaseType(context, targetData, "Long",    LLVMInt64Type()!!)
        KotlinBuiltIns.isFloat(this)            -> debugInfoBaseType(context, targetData, "Float",   LLVMFloatType()!!)
        KotlinBuiltIns.isDouble(this)           -> debugInfoBaseType(context, targetData, "Double",  LLVMDoubleType()!!)
        (!KotlinBuiltIns.isPrimitiveType(this)) -> debugInfoBaseType(context, targetData, "Any?",    LLVMPointerType(LLVMInt64Type(), 0)!!)
        else                                    -> TODO(toString())
    }
}

@Suppress("UNCHECKED_CAST")
private fun debugInfoBaseType(context:Context, targetData:LLVMTargetDataRef, typeName:String, type:LLVMTypeRef) = DICreateBasicType(
        context.debugInfo.builder, typeName,
        LLVMSizeOfTypeInBits(targetData, type),
        LLVMPreferredAlignmentOfType(targetData, type).toLong(), 0) as DITypeOpaqueRef

internal val FunctionDescriptor.types:List<KotlinType>
    get() {
        val parameters = valueParameters.map{it.type}
        return if (returnType != null) listOf(returnType!!, *parameters.toTypedArray()) else parameters
    }