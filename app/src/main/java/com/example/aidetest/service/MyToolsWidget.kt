package com.example.aidetest

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class MyToolsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { update(context,manager,ids) }
    companion object {
        fun update(context:Context) { val manager=AppWidgetManager.getInstance(context); update(context,manager,manager.getAppWidgetIds(ComponentName(context,MyToolsWidget::class.java))) }
        private fun update(context:Context,manager:AppWidgetManager,ids:IntArray){ val db=FinanceDb(context); val range=db.monthRange(); val income=db.totalByType("masuk",range.first,range.second); val expense=db.totalByType("keluar",range.first,range.second); val views=RemoteViews(context.packageName,R.layout.widget_finance); views.setTextViewText(R.id.widgetBalance,MoneyFormatter.format(income-expense)); views.setTextViewText(R.id.widgetExpense,"Hari ini / bulan ini: ${MoneyFormatter.format(expense)}"); val i=Intent(context,MainActivity::class.java).putExtra("open_finance",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); views.setOnClickPendingIntent(R.id.widgetRoot,android.app.PendingIntent.getActivity(context,101,i,android.app.PendingIntent.FLAG_UPDATE_CURRENT or if(android.os.Build.VERSION.SDK_INT>=23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)); ids.forEach{manager.updateAppWidget(it,views)} }
    }
}
