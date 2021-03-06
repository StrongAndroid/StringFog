package com.github.megatronking.stringfog.plugin;

import com.github.megatronking.stringfog.lib.Base64Fog;
import com.github.megatronking.stringfog.plugin.utils.TextUtils;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Visit the class to execute string fog.
 *
 * @author Megatron King
 * @since 2017/3/6 20:37
 */

public class StringFogClassVisitor extends ClassVisitor {

    private static final String IGNORE_ANNOTATION = "Lcom/github/megatronking/stringfog/lib/annotation/StringFogIgnore;";
    private static final String BASE64_FOG_CLASS_NAME = Base64Fog.class.getName().replace('.', '/');

    private boolean isClInitExists;

    private List<ClassStringField> mStaticFinalFields = new ArrayList<>();
    private List<ClassStringField> mStaticFields = new ArrayList<>();
    private List<ClassStringField> mFinalFields = new ArrayList<>();
    private List<ClassStringField> mFields = new ArrayList<>();

    private final String mKey;
    private String mClassName;

    private boolean mIgnoreClass;

    public StringFogClassVisitor(String key, ClassWriter cw) {
        super(Opcodes.ASM5, cw);
        this.mKey = key;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.mClassName = name;
//        System.out.println("processClass: " + mClassName);
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        mIgnoreClass = IGNORE_ANNOTATION.equals(desc);
        return super.visitAnnotation(desc, visible);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        if (ClassStringField.STRING_DESC.equals(desc) && name != null && !mIgnoreClass) {
            // static final, in this condition, the value is null or not null.
            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0) {
                mStaticFinalFields.add(new ClassStringField(name, (String) value));
                value = null;
            }
            // static, in this condition, the value is null.
            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) == 0) {
                mStaticFields.add(new ClassStringField(name, (String) value));
                value = null;
            }

            // final, in this condition, the value is null or not null.
            if ((access & Opcodes.ACC_STATIC) == 0 && (access & Opcodes.ACC_FINAL) != 0) {
                mFinalFields.add(new ClassStringField(name, (String) value));
                value = null;
            }

            // normal, in this condition, the value is null.
            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0) {
                mFields.add(new ClassStringField(name, (String) value));
                value = null;
            }
        }
        return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        if (mv != null && !mIgnoreClass) {
            if ("<clinit>".equals(name)) {
                isClInitExists = true;
                // If clinit exists meaning the static fields (not final) would have be inited here.
                mv = new MethodVisitor(Opcodes.ASM5, mv) {

                    private String lastStashCst;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // Here init static final fields.
                        for (ClassStringField field : mStaticFinalFields) {
                            if (field.value == null) {
                                continue;
                            }
                            super.visitLdcInsn(Base64Fog.encode(field.value, mKey));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BASE64_FOG_CLASS_NAME, "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitFieldInsn(Opcodes.PUTSTATIC, mClassName, field.name, ClassStringField.STRING_DESC);
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object cst) {
                        // Here init static or static final fields, but we must check field name int 'visitFieldInsn'
                        if (cst != null && cst instanceof String && !TextUtils.isEmptyAfterTrim((String) cst)) {
                            lastStashCst = (String) cst;
                            super.visitLdcInsn(Base64Fog.encode(lastStashCst, mKey));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BASE64_FOG_CLASS_NAME, "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
                        } else {
                            lastStashCst = null;
                            super.visitLdcInsn(cst);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        if (mClassName.equals(owner) && lastStashCst != null) {
                            boolean isContain = false;
                            for (ClassStringField field : mStaticFields) {
                                if (field.name.equals(name)) {
                                    isContain = true;
                                    break;
                                }
                            }
                            if (!isContain) {
                                for (ClassStringField field : mStaticFinalFields) {
                                    if (field.name.equals(name) && field.value == null) {
                                        field.value = lastStashCst;
                                        break;
                                    }
                                }
                            }
                        }
                        lastStashCst = null;
                        super.visitFieldInsn(opcode, owner, name, desc);
                    }
                };

            } else if ("<init>".equals(name)) {
                // Here init final(not static) and normal fields
                mv = new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitLdcInsn(Object cst) {
                        // We don't care about whether the field is final or normal
                        if (cst != null && cst instanceof String && !TextUtils.isEmptyAfterTrim((String) cst)) {
                            super.visitLdcInsn(Base64Fog.encode((String) cst, mKey));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BASE64_FOG_CLASS_NAME, "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
                        } else {
                            super.visitLdcInsn(cst);
                        }
                    }
                };
            } else {
                mv = new MethodVisitor(Opcodes.ASM5, mv) {

                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst != null && cst instanceof String && !TextUtils.isEmptyAfterTrim((String) cst)) {
                            // If the value is a static final field
                            for (ClassStringField field : mStaticFinalFields) {
                                if (cst.equals(field.value)) {
                                    super.visitFieldInsn(Opcodes.GETSTATIC, mClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }
                            // If the value is a final field (not static)
                            for (ClassStringField field : mFinalFields) {
                                // if the value of a final field is null, we ignore it
                                if (cst.equals(field.value)) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitFieldInsn(Opcodes.GETFIELD, mClassName, field.name, "Ljava/lang/String;");
                                    return;
                                }
                            }
                            // local variables
                            super.visitLdcInsn(Base64Fog.encode((String) cst, mKey));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BASE64_FOG_CLASS_NAME, "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
                            return;
                        }
                        super.visitLdcInsn(cst);
                    }

                };
            }
        }
        return mv;
    }

    @Override
    public void visitEnd() {
        if (!mIgnoreClass && !isClInitExists && !mStaticFinalFields.isEmpty()) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            // Here init static final fields.
            for (ClassStringField field : mStaticFinalFields) {
                if (field.value == null) {
                    continue; // It could not be happened
                }
                mv.visitLdcInsn(Base64Fog.encode(field.value, mKey));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BASE64_FOG_CLASS_NAME, "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, mClassName, field.name, ClassStringField.STRING_DESC);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        super.visitEnd();
    }
}
