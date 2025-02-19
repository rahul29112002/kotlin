/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.symbols

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.analysis.api.fir.findPsi
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.analysis.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.analysis.api.fir.utils.firRef
import org.jetbrains.kotlin.analysis.api.fir.utils.weakRef
import org.jetbrains.kotlin.analysis.api.symbols.KtTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal class KtFirTypeParameterSymbol(
    fir: FirTypeParameter,
    resolveState: FirModuleResolveState,
    override val token: ValidityToken,
    _builder: KtSymbolByFirBuilder
) : KtTypeParameterSymbol(), KtFirSymbol<FirTypeParameter> {
    private val builder by weakRef(_builder)
    override val firRef = firRef(fir, resolveState)
    override val psi: PsiElement? by firRef.withFirAndCache { fir -> fir.findPsi(fir.moduleData.session) }

    override val name: Name get() = firRef.withFir { it.name }

    override val upperBounds: List<KtType> by firRef.withFirAndCache(FirResolvePhase.TYPES) { fir ->
        fir.bounds.map { type -> builder.typeBuilder.buildKtType(type) }
    }

    override val variance: Variance get() = firRef.withFir { it.variance }
    override val isReified: Boolean get() = firRef.withFir { it.isReified }

    override fun createPointer(): KtSymbolPointer<KtTypeParameterSymbol> {
        KtPsiBasedSymbolPointer.createForSymbolFromSource(this)?.let { return it }
        TODO("Creating symbols for library type parameters is not supported yet")
    }

    override fun equals(other: Any?): Boolean = symbolEquals(other)
    override fun hashCode(): Int = symbolHashCode()
}
