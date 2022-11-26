package org.edx.mobile.viewModel

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.edx.mobile.exception.ErrorMessage
import org.edx.mobile.extenstion.decodeToLong
import org.edx.mobile.http.model.NetworkResponseCallback
import org.edx.mobile.http.model.Result
import org.edx.mobile.inapppurchases.BillingProcessor
import org.edx.mobile.model.api.EnrolledCoursesResponse
import org.edx.mobile.model.api.getAuditCoursesSku
import org.edx.mobile.model.iap.AddToBasketResponse
import org.edx.mobile.model.iap.CheckoutResponse
import org.edx.mobile.model.iap.ExecuteOrderResponse
import org.edx.mobile.module.analytics.Analytics
import org.edx.mobile.module.analytics.InAppPurchasesAnalytics
import org.edx.mobile.repository.InAppPurchasesRepository
import org.edx.mobile.util.InAppPurchasesException
import org.edx.mobile.util.InAppPurchasesUtils
import org.edx.mobile.util.observer.Event
import org.edx.mobile.util.observer.postEvent
import javax.inject.Inject

@HiltViewModel
class InAppPurchasesViewModel @Inject constructor(
    private val billingProcessor: BillingProcessor,
    private val repository: InAppPurchasesRepository,
    private val iapAnalytics: InAppPurchasesAnalytics
) : ViewModel() {

    private val _productPrice = MutableLiveData<Event<SkuDetails>>()
    val productPrice: LiveData<Event<SkuDetails>> = _productPrice

    private val _launchPurchaseFlow = MutableLiveData<Any?>()
    val launchPurchaseFlow: LiveData<Any?> = _launchPurchaseFlow

    private val _productPurchased = MutableLiveData<Event<Purchase>>()
    val productPurchased: LiveData<Event<Purchase>> = _productPurchased

    private val _executeResponse = MutableLiveData<ExecuteOrderResponse>()
    val executeResponse: LiveData<ExecuteOrderResponse> = _executeResponse

    private val _showFullscreenLoaderDialog = MutableLiveData<Event<Boolean>>()
    val showFullscreenLoaderDialog: LiveData<Event<Boolean>> = _showFullscreenLoaderDialog

    private val _purchaseFlowComplete = MutableLiveData(false)
    val purchaseFlowComplete: LiveData<Boolean> = _purchaseFlowComplete

    private val _refreshCourseData = MutableLiveData<Event<Boolean>>()
    val refreshCourseData: LiveData<Event<Boolean>> = _refreshCourseData

    private val _fakeUnfulfilledCompletion = MutableLiveData<Boolean>()
    val completedFakeUnfulfilledPurchase: LiveData<Boolean> = _fakeUnfulfilledCompletion

    private val _showLoader = MutableLiveData<Event<Boolean>>()
    val showLoader: LiveData<Event<Boolean>> = _showLoader

    private val _errorMessage = MutableLiveData<ErrorMessage?>()
    val errorMessage: LiveData<ErrorMessage?> = _errorMessage

    var upgradeMode = UpgradeMode.NORMAL
    var productId: String = ""

    private var _isVerificationPending = true
    val isVerificationPending: Boolean
        get() = _isVerificationPending

    private var basketId: Long = 0
    private var purchaseToken: String = ""
    private var incompletePurchases: MutableList<Pair<String, String>> = arrayListOf()

    private val listener = object : BillingProcessor.BillingFlowListeners {
        override fun onPurchaseCancel(responseCode: Int, message: String) {
            super.onPurchaseCancel(responseCode, message)
            dispatchError(
                requestType = ErrorMessage.PAYMENT_SDK_CODE,
                throwable = InAppPurchasesException(responseCode, message)
            )
            endLoading()
        }

        override fun onPurchaseComplete(purchase: Purchase) {
            super.onPurchaseComplete(purchase)
            purchaseToken = purchase.purchaseToken
            _productPurchased.postEvent(purchase)
            iapAnalytics.trackIAPEvent(eventName = Analytics.Events.IAP_PAYMENT_TIME)
            iapAnalytics.initUnlockContentTime()
        }
    }

    init {
        billingProcessor.setUpBillingFlowListeners(listener)
        billingProcessor.startConnection()
    }

    fun initializeProductPrice(courseSku: String?) {
        iapAnalytics.initPriceTime()
        courseSku?.let {
            billingProcessor.querySyncDetails(
                productId = courseSku
            ) { billingResult, skuDetails ->
                val skuDetail = skuDetails?.get(0)
                skuDetail?.let {
                    if (it.sku == courseSku) {
                        _productPrice.postEvent(it)
                        iapAnalytics.setPrice(skuDetail.price)
                        iapAnalytics.trackIAPEvent(Analytics.Events.IAP_LOAD_PRICE_TIME)
                    }
                } ?: dispatchError(
                    requestType = ErrorMessage.PRICE_CODE,
                    throwable = InAppPurchasesException(
                        httpErrorCode = billingResult.responseCode,
                        errorMessage = billingResult.debugMessage,
                    )
                )
            }
        } ?: dispatchError(requestType = ErrorMessage.PRICE_CODE)
    }

    fun startPurchaseFlow(productId: String) {
        this.productId = productId
        addProductToBasket(productId = productId)
    }

    private fun addProductToBasket(productId: String) {
        startLoading()
        repository.addToBasket(
            productId = productId,
            callback = object : NetworkResponseCallback<AddToBasketResponse> {
                override fun onSuccess(result: Result.Success<AddToBasketResponse>) {
                    result.data?.let {
                        proceedCheckout(it.basketId)
                    } ?: endLoading()
                }

                override fun onError(error: Result.Error) {
                    dispatchError(
                        requestType = ErrorMessage.ADD_TO_BASKET_CODE,
                        throwable = error.throwable
                    )
                    endLoading()
                }
            })
    }

    fun proceedCheckout(basketId: Long) {
        this.basketId = basketId
        repository.proceedCheckout(
            basketId = basketId,
            callback = object : NetworkResponseCallback<CheckoutResponse> {
                override fun onSuccess(result: Result.Success<CheckoutResponse>) {
                    result.data?.let {
                        if (upgradeMode.isSilentMode()) {
                            executeOrder()
                        } else {
                            iapAnalytics.initPaymentTime()
                            _launchPurchaseFlow.postValue(null)
                        }
                    } ?: endLoading()
                }

                override fun onError(error: Result.Error) {
                    dispatchError(
                        requestType = ErrorMessage.CHECKOUT_CODE,
                        throwable = error.throwable
                    )
                    endLoading()
                }
            })
    }

    fun purchaseItem(activity: Activity, userId: Long, courseSku: String?) {
        courseSku?.let {
            billingProcessor.purchaseItem(activity, courseSku, userId)
        }
    }

    fun executeOrder() {
        _isVerificationPending = false
        repository.executeOrder(
            basketId = basketId,
            productId = productId,
            purchaseToken = purchaseToken,
            callback = object : NetworkResponseCallback<ExecuteOrderResponse> {
                override fun onSuccess(result: Result.Success<ExecuteOrderResponse>) {
                    result.data?.let {
                        orderExecuted()
                        if (upgradeMode.isSilentMode()) {
                            markPurchaseComplete(it)
                        } else {
                            _executeResponse.postValue(it)
                            refreshCourseData(true)
                        }
                    }
                    endLoading()
                }

                override fun onError(error: Result.Error) {
                    dispatchError(
                        requestType = ErrorMessage.EXECUTE_ORDER_CODE,
                        throwable = error.throwable
                    )
                    endLoading()
                }
            })
    }

    /**
     * To detect and handle courses which are purchased but still not Verified
     *
     * @param userId: id of the user to detect un full filled purchases and process.
     * @return enrolledCourses user enrolled courses in the platform.
     */
    fun detectUnfulfilledPurchase(userId: Long, enrolledCourses: List<EnrolledCoursesResponse>) {
        val auditCoursesSku = enrolledCourses.getAuditCoursesSku()
        if (auditCoursesSku.isEmpty()) {
            _fakeUnfulfilledCompletion.postValue(true)
            return
        }
        billingProcessor.queryPurchase { _, purchases ->
            if (purchases.isEmpty()) {
                return@queryPurchase
            }
            val purchasesList =
                purchases.filter { it.accountIdentifiers?.obfuscatedAccountId?.decodeToLong() == userId }
                    .associate { it.skus[0] to it.purchaseToken }
                    .toList()
            if (purchasesList.isNotEmpty()) {
                incompletePurchases = InAppPurchasesUtils.getInCompletePurchases(
                    auditCoursesSku,
                    purchasesList
                )
                if (incompletePurchases.isEmpty()) {
                    _fakeUnfulfilledCompletion.postValue(true)
                } else {
                    startUnfulfilledVerification()
                }
            } else {
                _fakeUnfulfilledCompletion.postValue(true)
            }
        }
    }

    /**
     * Method to start the process to verify the un full filled courses skus
     */
    private fun startUnfulfilledVerification() {
        viewModelScope.launch {
            upgradeMode = UpgradeMode.SILENT
            purchaseToken = incompletePurchases[0].second
            addProductToBasket(incompletePurchases[0].first)
        }
    }

    private fun markPurchaseComplete(result: ExecuteOrderResponse) {
        incompletePurchases = incompletePurchases.drop(1).toMutableList()
        if (incompletePurchases.isEmpty()) {
            _executeResponse.postValue(result)
        } else {
            startUnfulfilledVerification()
        }
    }

    fun dispatchError(
        requestType: Int = 0,
        errorMessage: String? = null,
        throwable: Throwable? = null
    ) {
        var actualErrorMessage = errorMessage
        throwable?.let {
            if (errorMessage.isNullOrBlank()) {
                actualErrorMessage = it.message
            }
        }
        if (throwable != null && throwable is InAppPurchasesException) {
            _errorMessage.postValue(ErrorMessage(requestType = requestType, throwable = throwable))
        } else {
            _errorMessage.postValue(
                ErrorMessage(
                    requestType = requestType,
                    throwable = InAppPurchasesException(errorMessage = actualErrorMessage)
                )
            )
        }
    }

    private fun orderExecuted() {
        productId = ""
        basketId = 0
    }

    fun errorMessageShown() {
        _errorMessage.postValue(null)
    }

    private fun startLoading() {
        _showLoader.postEvent(true)
    }

    fun endLoading() {
        _showLoader.postEvent(false)
    }

    fun showFullScreenLoader(show: Boolean) {
        _showFullscreenLoaderDialog.postEvent(show)
    }

    fun refreshCourseData(refresh: Boolean) {
        _refreshCourseData.postEvent(refresh)
    }

    // To refrain the View Model from emitting further observable calls
    fun resetPurchase(complete: Boolean) {
        upgradeMode = UpgradeMode.NORMAL
        _isVerificationPending = true
        _purchaseFlowComplete.postValue(complete)
    }

    override fun onCleared() {
        billingProcessor.disconnect()
        super.onCleared()
    }

    enum class UpgradeMode {
        NORMAL,
        SILENT;

        fun isSilentMode() = this == SILENT

        fun isNormalMode() = this == NORMAL
    }
}
