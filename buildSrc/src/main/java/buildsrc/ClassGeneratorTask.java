/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package buildsrc;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

abstract class ClassGeneratorTask extends DefaultTask {
    @OutputFile
    abstract RegularFileProperty getOutputFile();

    public ClassGeneratorTask() {
        // I don't think this is necessary, as the build cache should use the hash of this compiled class as the cache key
        // But just in case, this is how to make it always run.
        this.getOutputs().upToDateWhen(t -> false);
        this.getOutputFile().convention(this.getProject()
            .getLayout()
            .getBuildDirectory()
            .file(this.getName() + "/JavaProbe.class")
        );
    }

    @TaskAction
    public void run() throws IOException {
        Path output = getOutputFile().getAsFile().get().toPath();
        byte[] data = buildClass();
        Files.write(output, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private byte[] buildClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_1, ACC_PUBLIC | ACC_SUPER, "JavaProbe", null, "java/lang/Object", null);

        constructor(writer);
        main(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private void constructor(ClassVisitor writer) {
        MethodVisitor mtd = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mtd.visitCode();
        mtd.visitVarInsn(ALOAD, 0);
        mtd.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mtd.visitInsn(RETURN);
        mtd.visitMaxs(1, 1);
        mtd.visitEnd();
    }

    private void main(ClassVisitor classWriter) {
        MethodVisitor mtd = classWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        mtd.visitCode();

        for (String prop : new String[] {
            "java.home",
            "java.version",
            "java.vendor",
            "java.runtime.name",
            "java.runtime.version",
            "java.vm.name",
            "java.vm.version",
            "java.vm.vendor",
            "os.arch"
        }) {
            String prefix = "JAVA_PROBE: " + prop + " ";
            // System.out.print(prefix);
            mtd.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mtd.visitLdcInsn(prefix);
            mtd.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);

            // System.out.println(System.getProperty(prop, "unset"));
            mtd.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mtd.visitLdcInsn(prop);
            mtd.visitLdcInsn("unset");
            mtd.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
            mtd.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        }

        mtd.visitInsn(RETURN);

        mtd.visitMaxs(3, 2);

        mtd.visitEnd();
    }
}
