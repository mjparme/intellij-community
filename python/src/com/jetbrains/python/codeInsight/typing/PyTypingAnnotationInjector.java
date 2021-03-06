/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.jetbrains.python.codeInsight.typing;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.codeInsight.PyInjectionUtil;
import com.jetbrains.python.codeInsight.PyInjectorBase;
import com.jetbrains.python.codeInsight.functionTypeComments.PyFunctionTypeAnnotationDialect;
import com.jetbrains.python.documentation.doctest.PyDocstringLanguageDialect;
import com.jetbrains.python.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Injects fragments for type annotations either in string literals (quoted annotations containing forward references) or
 * in type comments starting with <tt># type:</tt>.
 *
 * @author vlan
 */
public class PyTypingAnnotationInjector extends PyInjectorBase {
  public static final Pattern RE_TYPING_ANNOTATION = Pattern.compile("\\s*\\S+(\\[.*\\])?\\s*");

  @Override
  protected PyInjectionUtil.InjectionResult registerInjection(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
    // Handles only string literals containing quoted types
    final PyInjectionUtil.InjectionResult result = super.registerInjection(registrar, context);
    
    if (result == PyInjectionUtil.InjectionResult.EMPTY &&
        context instanceof PsiComment &&
        context instanceof PsiLanguageInjectionHost &&
        context.getContainingFile() instanceof PyFile) {
      return registerCommentInjection(registrar, (PsiLanguageInjectionHost)context);
    }
    return result;
  }

  @Nullable
  @Override
  public Language getInjectedLanguage(@NotNull PsiElement context) {
    if (context instanceof PyStringLiteralExpression) {
      final PyStringLiteralExpression expr = (PyStringLiteralExpression)context;
      if (PsiTreeUtil.getParentOfType(context, PyAnnotation.class, true, PyCallExpression.class) != null &&
          isTypingAnnotation(expr.getStringValue())) {
        return PyDocstringLanguageDialect.getInstance();
      }
    }
    return null;
  }

  @NotNull
  private static PyInjectionUtil.InjectionResult registerCommentInjection(@NotNull MultiHostRegistrar registrar,
                                                                          @NotNull PsiLanguageInjectionHost host) {
    final String text = host.getText();
    final String annotationText = PyTypingTypeProvider.getTypeCommentValue(text);
    if (annotationText != null) {
      final Language language;
      if (PyTypingTypeProvider.IGNORE.equals(annotationText)) {
        language = null;
      }
      else if (isFunctionTypeComment(host)) {
        language = PyFunctionTypeAnnotationDialect.INSTANCE;
      }
      else {
        language = PyDocstringLanguageDialect.getInstance();
      }
      if (language != null) {
        registrar.startInjecting(language);
        //noinspection ConstantConditions
        registrar.addPlace("", "", host, PyTypingTypeProvider.getTypeCommentValueRange(text));
        registrar.doneInjecting();
        return new PyInjectionUtil.InjectionResult(true, true);
      }
    }
    return PyInjectionUtil.InjectionResult.EMPTY;
  }

  private static boolean isFunctionTypeComment(@NotNull PsiElement comment) {
   final PyFunction function = PsiTreeUtil.getParentOfType(comment, PyFunction.class);
    return function != null && function.getTypeComment() == comment;
  }

  private static boolean isTypingAnnotation(@NotNull String s) {
    return RE_TYPING_ANNOTATION.matcher(s).matches();
  }
}
